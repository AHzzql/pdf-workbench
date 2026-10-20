package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.pdfworkbench.controller.ExtractionController;
import cn.edu.pdfworkbench.exception.*;
import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.service.PdfService;
import cn.edu.pdfworkbench.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PdfOutputFailureTest {
  @TempDir Path directory;

  @Test
  void missingOutputParentIsNotReportedAsInvalidPdf() throws Exception {
    Path input = directory.resolve("input.pdf"), output = directory.resolve("missing/output.pdf");
    try (var document = new PDDocument()) {
      document.addPage(new PDPage()); document.save(input.toFile());
    }
    byte[] original = Files.readAllBytes(input);
    var pdf = assertThrows(PdfProcessingException.class, () -> PdfEncryptionUtils.encrypt(input, null,
        output, new EncryptionOptions("reader", "owner", 256, true, true, true)));
    assertEquals("OUTPUT_CREATE_FAILED", pdf.getCode());
    var table = new TableData(1, new Rect(0,0,1,1), List.of(List.of("cell")));
    var csv = assertThrows(PdfProcessingException.class, () -> PdfTableUtils.writeCsv(table, output));
    assertEquals("OUTPUT_CREATE_FAILED", csv.getCode());
    assertArrayEquals(original, Files.readAllBytes(input));
    assertFalse(Files.exists(output));
  }

  @Test
  void storageFailureReturns500WithoutLocalPaths() throws Exception {
    Path missing = directory.resolve("missing");
    var service = new PdfService(new ObjectMapper(), 1, 200, 100000, missing.toString());
    var mvc = MockMvcBuilders.standaloneSetup(new ExtractionController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    byte[] pdf;
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage()); document.save(output); pdf = output.toByteArray();
    }
    var response = mvc.perform(multipart("/api/v1/pdf/info").file(new MockMultipartFile("file", "input.pdf", "application/pdf", pdf)))
        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("OUTPUT_CREATE_FAILED"))
        .andReturn().getResponse().getContentAsString();
    assertFalse(response.contains(directory.toString()));
  }
}
