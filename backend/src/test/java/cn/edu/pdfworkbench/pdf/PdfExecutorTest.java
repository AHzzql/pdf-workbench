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
}
