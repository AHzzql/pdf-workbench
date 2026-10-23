package cn.edu.pdfworkbench.pdf;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.ai.ParsedReadingDocument;
import cn.edu.pdfworkbench.exception.*;
import cn.edu.pdfworkbench.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class PdfExecutorTest {
  @TempDir Path root;
  final ObjectMapper mapper=new ObjectMapper();
  private MockMultipartFile pdf(boolean encrypted,boolean text) throws IOException {
    try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()) {
      var page=new PDPage(); document.addPage(page);
      if(text) try(var content=new PDPageContentStream(document,page)) {
        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
        content.newLineAtOffset(40,700); content.showText("Shared PDF boundary"); content.endText();
      }
      if(encrypted) document.protect(new StandardProtectionPolicy("owner","reader",new AccessPermission()));
      document.save(bytes); return new MockMultipartFile("file","sample.pdf","application/pdf",bytes.toByteArray());
    }
  }
  private void empty() throws IOException { try(var files=Files.list(root)) { assertEquals(0,files.count()); } }
  @Test void readingReturnsContentAndCleansWorkspace() throws Exception {
    try(var executor=new PdfExecutor(mapper,1,200,1000000,root.toString())) {
      var parsed=executor.execute(pdf(false,true),null,"READING",null,Map.of(),(input,output,result)-> {
        assertTrue(Files.isRegularFile(input));
        assertEquals("application/json;charset=UTF-8",result.contentType());
        return mapper.readValue(output.toFile(),ParsedReadingDocument.class);
      });
      assertEquals(1,parsed.pages()); assertFalse(parsed.encrypted());
      assertTrue(parsed.content().chunks().stream().anyMatch(c->c.text().contains("Shared PDF boundary")));
    }
    empty();
  }
  @Test void passwordErrorsRetainCodesAndNextRequestSucceeds() throws Exception {
    try(var executor=new PdfExecutor(mapper,1,200,1000000,root.toString())) {
      var file=pdf(true,true);
      for(String password:new String[]{null,"wrong"}) {
        var error=assertThrows(PdfProcessingException.class,()->executor.execute(file,password,"INFO",null,Map.of(),(i,o,r)->null));
        assertEquals(password==null?"PDF_PASSWORD_REQUIRED":"PDF_PASSWORD_INVALID",error.getCode()); empty();
      }
      executor.execute(file,"reader","INFO",null,Map.of(),(i,o,r)->{ assertTrue(Files.size(o)>0); return null; });
    }
    empty();
  }
  @Test void malformedAndBlankReadingRetainFailureSemantics() throws Exception {
    try(var executor=new PdfExecutor(mapper,1,200,1000000,root.toString())) {
      assertThrows(IOException.class,()->executor.execute(new MockMultipartFile("file","%PDF-broken".getBytes()),null,"INFO",null,Map.of(),(i,o,r)->null));
      empty();
      var error=assertThrows(ApiException.class,()->executor.execute(pdf(false,false),null,"READING",null,Map.of(),(i,o,r)->null));
      assertEquals(422,error.status()); assertEquals("NO_TEXT",error.code());
    }
    empty();
  }
  @Test void invalidRegexAndOutputLimitReleaseSlot() throws Exception {
    try(var executor=new PdfExecutor(mapper,1,200,1000000,root.toString())) {
      var error=assertThrows(PdfParameterException.class,()->executor.execute(pdf(false,true),null,"SEARCH",new SearchOptions(null,"[",0),Map.of(),(i,o,r)->null));
      assertEquals("INVALID_REGEX",error.getCode());
    }
    empty();
    try(var executor=new PdfExecutor(mapper,1,200,1,root.toString())) {
      assertEquals(413,assertThrows(ApiException.class,()->executor.execute(pdf(false,true),null,"INFO",null,Map.of(),(i,o,r)->null)).status());
    }
    empty();
  }
  @Test void oversizedParametersNeverReachChild() throws Exception {
    try(var executor=new PdfExecutor(mapper,1,200,1000000,root.toString())) {
      var error=assertThrows(ApiException.class,()->executor.execute(pdf(false,true),"x".repeat(65536),"INFO",null,Map.of(),(i,o,r)->null));
      assertEquals("PDF_REQUEST_LIMIT",error.code());
    }
    empty();
  }
  @Test void requestDescriptionRedactsSecrets() {
    var request=new PdfJobRequest("SIGN","secret",mapper.valueToTree(Map.of("certificatePassword","private")),200,1000);
    assertFalse(request.toString().contains("secret")); assertFalse(request.toString().contains("private"));
  }
  @Test void actualUploadBytesAreBoundedIndependentlyOfDeclaredSize() throws Exception {
    var file = new MockMultipartFile("file", new byte[]{1}) {
      @Override public long getSize() { return 1; }
      @Override public InputStream getInputStream() {
        return new InputStream() {
          long remaining = 52428801L;
          @Override public int read() { return remaining-- > 0 ? 0 : -1; }
          @Override public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            Arrays.fill(buffer, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
          }
        };
      }
    };
    try (var executor = new PdfExecutor(mapper, 1, 200, 1000000, root.toString())) {
      var error = assertThrows(ApiException.class, () -> executor.execute(
          file, null, "INFO", null, Map.of(), (input, output, result) -> null));
      assertEquals(413, error.status());
      assertEquals("FILE_TOO_LARGE", error.code());
      empty();
      executor.execute(pdf(false, true), null, "INFO", null, Map.of(), (input, output, result) -> null);
    }
    empty();
  }

  @Test void toolsAndReadingUploadShareAdmissionCapacity() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var ordinary = pdf(false, true);
    var blocking = new MockMultipartFile("file", ordinary.getBytes()) {
      @Override public InputStream getInputStream() throws IOException {
        entered.countDown();
        try {
          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
        return super.getInputStream();
      }
    };
    try (var executor = new PdfExecutor(mapper, 1, 200, 1000000, root.toString());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var service = new cn.edu.pdfworkbench.service.PdfService(executor);
      var repository = org.mockito.Mockito.mock(cn.edu.pdfworkbench.ai.AiRepository.class);
      org.mockito.Mockito.when(repository.documents()).thenReturn(List.of());
      var documents = new cn.edu.pdfworkbench.ai.AiDocuments(repository,
          org.mockito.Mockito.mock(cn.edu.pdfworkbench.ai.AiSettings.class),
          org.mockito.Mockito.mock(cn.edu.pdfworkbench.ai.AiRetrieval.class),
          org.mockito.Mockito.mock(cn.edu.pdfworkbench.tasks.TaskStore.class), executor, mapper);
      var running = pool.submit(() -> {
        service.info(blocking, null, new org.springframework.mock.web.MockHttpServletResponse());
        return null;
      });
      try {
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        var error = assertThrows(ApiException.class, () -> documents.register(ordinary, null));
        assertEquals(429, error.status());
        assertEquals("CAPACITY_EXCEEDED", error.code());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).publishDocument(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      } finally {
        release.countDown();
      }
      running.get(15, java.util.concurrent.TimeUnit.SECONDS);
      executor.execute(ordinary, null, "READING", null, Map.of(), (input, output, result) -> {
        assertFalse(mapper.readValue(output.toFile(), ParsedReadingDocument.class).content().chunks().isEmpty());
        return null;
      });
    }
    empty();
  }

}
