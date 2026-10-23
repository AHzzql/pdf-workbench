package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.model.TextOptions;
import cn.edu.pdfworkbench.service.PdfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class PdfServiceLimitsTest {
  @TempDir Path directory;

  private byte[] pdf(int pages) throws Exception {
    try (var document = new PDDocument();
        var stream = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) document.addPage(new PDPage());
      document.save(stream);
      return stream.toByteArray();
    }
  }

  private PdfService service(int pages, long output) {
    return new PdfService(new ObjectMapper(), 1, pages, output, directory.toString());
  }

  private void empty() throws IOException {
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void pageAndOutputLimitsCleanUp() throws Exception {
    var file = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf(2));
    assertEquals(
        413,
        assertThrows(
                ApiException.class,
                () ->
                    service(1, 1000)
                        .text(
                            file, null, new TextOptions(null, true), new MockHttpServletResponse()))
            .status());
    empty();
    assertEquals(
        413,
        assertThrows(
                ApiException.class,
                () ->
                    service(200, 1)
                        .text(
                            file, null, new TextOptions(null, true), new MockHttpServletResponse()))
            .status());
    empty();
  }

  @Test
  void concurrencyLimitIsReleasedAfterRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var file =
        new MockMultipartFile("file", "a.pdf", "application/pdf", pdf(1)) {
          @Override
          public InputStream getInputStream() throws IOException {
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            return super.getInputStream();
          }
        };
    var service = service(200, 100000);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var request =
          executor.submit(
              () -> {
                service.text(
                    file, null, new TextOptions(null, true), new MockHttpServletResponse());
                return null;
              });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      try {
        assertEquals(
            429,
            assertThrows(
                    ApiException.class,
                    () ->
                        service.text(
                            file, null, new TextOptions(null, true), new MockHttpServletResponse()))
                .status());
      } finally {
        release.countDown();
      }
      request.get(10, TimeUnit.SECONDS);
    }
    service.text(
        new MockMultipartFile("file", pdf(1)),
        null,
        new TextOptions(null, true),
        new MockHttpServletResponse());
    empty();
  }

  @Test
  void interruptedResponseCleansUp() throws Exception {
    var response =
        new MockHttpServletResponse() {
          @Override
          public jakarta.servlet.ServletOutputStream getOutputStream() {
            return new jakarta.servlet.ServletOutputStream() {
              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(jakarta.servlet.WriteListener listener) {}

              @Override
              public void write(int value) throws IOException {
                throw new IOException("client disconnected");
              }
            };
          }
        };
    assertThrows(
        IOException.class,
        () ->
            service(200, 100000)
                .text(
                    new MockMultipartFile("file", pdf(1)),
                    null,
                    new TextOptions(null, true),
                    response));
    empty();
  }
  @Test
  void missingWorkDirectoryHasAnOutputFailureCodeAndReleasesSlot() throws Exception {
    Path unavailable = directory.resolve("missing");
    var service = new PdfService(new ObjectMapper(), 1, 200, 100000, unavailable.toString());
    var file = new MockMultipartFile("file", pdf(1));
    var error = assertThrows(cn.edu.pdfworkbench.exception.PdfProcessingException.class,
        () -> service.info(file, null, new MockHttpServletResponse()));
    assertEquals("OUTPUT_CREATE_FAILED", error.getCode());
    Files.createDirectory(unavailable);
    service.info(file, null, new MockHttpServletResponse());
    try (var files = Files.list(unavailable)) { assertEquals(0, files.count()); }
  }

}
