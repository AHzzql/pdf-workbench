package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfApiTest {
  static final Path TEMP;

  static {
    try {
      TEMP = Files.createTempDirectory("pdf-api-test-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("workbench.temporary-root", TEMP::toString);
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper mapper;
  byte[] sample;

  @BeforeEach
  void fixture() throws Exception {
    try (var doc = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      doc.getDocumentInformation().setTitle("API sample");
      try (var content = new PDPageContentStream(doc, page)) {
        for (int x = 60; x <= 260; x += 100) {
          content.moveTo(x, 500);
          content.lineTo(x, 580);
        }
        for (int y = 500; y <= 580; y += 40) {
          content.moveTo(60, y);
          content.lineTo(260, y);
        }
        content.stroke();
        PdfTextUtilsTest.text(content, "Name", 65, 560, 12, Color.BLACK);
        PdfTextUtilsTest.text(content, "Value", 165, 560, 12, Color.BLACK);
        PdfTextUtilsTest.text(content, "Alpha", 65, 520, 12, Color.BLACK);
        PdfTextUtilsTest.text(content, "42", 165, 520, 12, Color.BLACK);
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        content.drawImage(LosslessFactory.createFromImage(doc, image), 60, 620, 20, 20);
      }
      doc.save(bytes);
      sample = bytes.toByteArray();
    }
    Path evidence = Files.createDirectories(Path.of("target", "verification"));
    Files.write(evidence.resolve("api-sample.pdf"), sample);
  }

  @AfterEach
  void noRequestFilesRemain() throws Exception {
    try (var files = Files.list(TEMP)) {
      assertEquals(0, files.count());
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(TEMP);
  }

  private ByteArrayResource resource(byte[] bytes, String name) {
    return new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return name;
      }
    };
  }

  private ResponseEntity<byte[]> post(String endpoint, byte[] file, Map<String, Object> fields) {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", resource(file, "../../document.pdf"));
    fields.forEach(body::add);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity(
        "/api/v1/pdf" + endpoint, new HttpEntity<>(body, headers), byte[].class);
  }

  @Test
  void extractsTextSearchMetadataTablesCsvAndImages() throws Exception {
    var text = post("/text", sample, Map.of());
    assertEquals(200, text.getStatusCode().value());
    assertTrue(
        mapper
            .readTree(text.getBody())
            .path("pages")
            .get(0)
            .path("text")
            .asText()
            .contains("Alpha"));
    var search = post("/text/search", sample, Map.of("pattern", "Alpha"));
    assertEquals(1, mapper.readTree(search.getBody()).size());
    var info = post("/info", sample, Map.of());
    assertTrue(
        new String(info.getBody(), java.nio.charset.StandardCharsets.UTF_8).contains("API sample"));
    var tables = post("/tables", sample, Map.of());
    assertEquals(
        "42", mapper.readTree(tables.getBody()).get(0).path("rows").get(1).get(1).asText());
    var csv = post("/tables/csv", sample, Map.of("tableIndex", 0));
    assertEquals(200, csv.getStatusCode().value());
    assertTrue(
        new String(csv.getBody(), java.nio.charset.StandardCharsets.UTF_8)
            .contains("\"Alpha\",\"42\""));
    var images = post("/images", sample, Map.of("format", "PNG"));
    assertEquals(200, images.getStatusCode().value());
    Set<String> names = new HashSet<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(images.getBody()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
        assertFalse(entry.getName().contains(".."));
      }
    }
    assertTrue(names.contains("manifest.json"));
    assertTrue(names.stream().anyMatch(name -> name.endsWith(".png")));
  }

  @Test
  void modifiesAndReopensDownloads() throws Exception {
    var encrypted =
        post(
            "/encrypt",
            sample,
            Map.of("userPassword", "reader", "ownerPassword", "owner", "allowCopy", false));
    assertEquals(200, encrypted.getStatusCode().value());
    try (var doc = Loader.loadPDF(encrypted.getBody(), "reader")) {
      assertTrue(doc.isEncrypted());
      assertFalse(doc.getCurrentAccessPermission().canExtractContent());
    }
    var marked = post("/watermark", sample, Map.of("type", "TEXT", "text", "内部资料", "angle", 0));
    assertEquals(200, marked.getStatusCode().value());
    try (var doc = Loader.loadPDF(marked.getBody())) {
      assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("内部资料"));
    }
    var removed = post("/signatures/remove", sample, Map.of("confirmRemoval", true));
    assertEquals(200, removed.getStatusCode().value());
    try (var doc = Loader.loadPDF(removed.getBody())) {
      assertEquals(1, doc.getNumberOfPages());
    }
    assertEquals(0, mapper.readTree(post("/signatures/verify", sample, Map.of()).getBody()).size());
  }

  @Test
  void signsVerifiesAndRemovesThroughHttp() throws Exception {
    Path certificate = Files.createTempFile("api-certificate-", ".p12");
    try {
      new PdfSignatureUtilsTest().certificate(certificate);
      var signed =
          post(
              "/signatures/sign",
              sample,
              Map.of(
                  "certificate",
                  resource(Files.readAllBytes(certificate), "signer.p12"),
                  "certificatePassword",
                  "test-password"));
      assertEquals(200, signed.getStatusCode().value());
      var verified = post("/signatures/verify", signed.getBody(), Map.of());
      assertEquals("VALID", mapper.readTree(verified.getBody()).get(0).path("integrity").asText());
      var removed = post("/signatures/remove", signed.getBody(), Map.of("confirmRemoval", true));
      try (var doc = Loader.loadPDF(removed.getBody())) {
        assertTrue(doc.getSignatureDictionaries().isEmpty());
      }
    } finally {
      Files.deleteIfExists(certificate);
    }
  }

  @Test
  void rejectsInvalidInputWithoutSensitiveDetails() throws Exception {
    assertEquals(415, post("/text", "invalid".getBytes(), Map.of()).getStatusCode().value());
    assertEquals(422, post("/text", "%PDF-broken".getBytes(), Map.of()).getStatusCode().value());
    assertEquals(400, post("/text", sample, Map.of("pages", "9000")).getStatusCode().value());
    assertEquals(400, post("/text/search", sample, Map.of("pattern", "[")).getStatusCode().value());
    assertEquals(
        400,
        post("/signatures/remove", sample, Map.of("confirmRemoval", false))
            .getStatusCode()
            .value());
    var encrypted =
        post("/encrypt", sample, Map.of("userPassword", "reader", "ownerPassword", "owner"));
    var denied = post("/text", encrypted.getBody(), Map.of("password", "private-value"));
    assertEquals(403, denied.getStatusCode().value());
    String body = new String(denied.getBody());
    assertFalse(body.contains("private-value"));
    assertFalse(body.contains(TEMP.toString()));
    assertFalse(body.contains("stackTrace"));
  }

  @Test
  void exposesAllBusinessOperationsInSwagger() throws Exception {
    var document = http.getForObject("/v3/api-docs", String.class);
    var json = mapper.readTree(document);
    var originalPaths = new java.util.ArrayList<String>();
    json.path("paths")
        .fieldNames()
        .forEachRemaining(
            p -> {
              if (p.startsWith("/api/v1/pdf/") && !p.startsWith("/api/v1/pdf/ai/"))
                originalPaths.add(p);
            });
    assertEquals(11, originalPaths.size());
    assertTrue(
        json.path("paths")
            .path("/api/v1/pdf/signatures/sign")
            .path("post")
            .path("requestBody")
            .toString()
            .contains("certificate"));
    assertEquals(
        200, http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value());
    Files.writeString(Path.of("target", "verification", "openapi.json"), document);
    var schemas = json.path("components").path("schemas");
    assertTrue(schemas.path("PageText").path("properties").has("blocks"));
    assertTrue(schemas.path("DocumentInfo").path("properties").has("pageCount"));
    assertTrue(schemas.path("SignatureResult").path("properties").path("hasLaterRevision").path("nullable").asBoolean());
    assertTrue(schemas.path("SignatureResult").path("properties").path("integrity").path("enum").toString().contains("UNKNOWN"));
    assertFalse(schemas.path("SignatureResult").path("properties").path("hasLaterRevision").has("default"));
    assertTrue(json.path("paths").path("/api/v1/pdf/encrypt").path("post").path("requestBody")
        .path("content").path("multipart/form-data").path("schema").path("properties")
        .path("ownerPassword").path("writeOnly").asBoolean());
    Files.writeString(Path.of("target", "verification", "openapi.json"), document);
  }

  @Test
  void rejectsWrongProtocolAndOversizedUpload() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    assertEquals(
        415,
        http.postForEntity("/api/v1/pdf/text", new HttpEntity<>("{}", headers), String.class)
            .getStatusCode()
            .value());
    assertEquals(405, http.getForEntity("/api/v1/pdf/text", String.class).getStatusCode().value());
    assertEquals(
        400, post("/text?password=not-accepted", sample, Map.of()).getStatusCode().value());
    assertEquals(413, post("/text", new byte[51 * 1024 * 1024], Map.of()).getStatusCode().value());
  }
  private void errorCode(ResponseEntity<byte[]> response, int status, String code) throws Exception {
    assertEquals(status, response.getStatusCode().value());
    var error = mapper.readTree(response.getBody());
    assertEquals(status, error.path("status").asInt());
    assertEquals(code, error.path("code").asText());
  }

  @Test
  void distinguishesBusinessErrorsAndReturnsCoreProperties() throws Exception {
    errorCode(post("/text", sample, Map.of("pages", "9000")), 400, "INVALID_PAGES");
    errorCode(post("/text/search", sample, Map.of("pattern", "[")), 400, "INVALID_REGEX");
    var info = mapper.readTree(post("/info", sample, Map.of()).getBody());
    assertEquals(1, info.path("pageCount").asInt());
    var text = mapper.readTree(post("/text", sample, Map.of()).getBody()).path("pages").get(0);
    assertTrue(text.path("blocks").isArray());
    assertTrue(text.path("blocks").size() < text.path("spans").size());
    var encrypted = post("/encrypt", sample, Map.of("userPassword", "reader", "ownerPassword", "owner"));
    errorCode(post("/info", encrypted.getBody(), Map.of()), 403, "PDF_PASSWORD_REQUIRED");
    errorCode(post("/info", encrypted.getBody(), Map.of("password", "wrong")), 403, "PDF_PASSWORD_INVALID");
    try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
      document.addPage(new PDPage()); document.save(bytes);
      errorCode(post("/images", bytes.toByteArray(), Map.of()), 422, "NO_IMAGES_FOUND");
      Files.write(Path.of("target", "verification", "no-images.pdf"), bytes.toByteArray());
    }
    var images = post("/images", sample, Map.of());
    assertEquals("1", images.getHeaders().getFirst("X-Images-Total"));
    assertEquals("1", images.getHeaders().getFirst("X-Images-Exported"));
    try (var zip = new ZipInputStream(new ByteArrayInputStream(images.getBody()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals("manifest.json")) {
          var item = mapper.readTree(zip.readAllBytes()).get(0);
          assertEquals("EXPORTED", item.path("status").asText());
          assertTrue(item.path("rawFile").asText().endsWith(".bin"));
        }
      }
    }
  }

  @Test
  void reportsPartialImageExportsWithRawFilesAndDiagnostics() throws Exception {
    byte[] bytes;
    try (var document = Loader.loadPDF(sample); var stream = new ByteArrayOutputStream()) {
      var page = document.getPage(0);
      for (var name : page.getResources().getXObjectNames()) {
        var object = page.getResources().getXObject(name);
        if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image)
          image.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.FILTER,
              org.apache.pdfbox.cos.COSName.getPDFName("UnsupportedTestFilter"));
      }
      try (var content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true)) {
        content.drawImage(LosslessFactory.createFromImage(document, new BufferedImage(7,7,BufferedImage.TYPE_INT_RGB)), 80, 620, 20, 20);
      }
      document.save(stream); bytes = stream.toByteArray();
    }
    Files.write(Path.of("target", "verification", "partial-images.pdf"), bytes);
    var response = post("/images", bytes, Map.of());
    assertEquals(200, response.getStatusCode().value());
    assertEquals("2", response.getHeaders().getFirst("X-Images-Total"));
    assertEquals("1", response.getHeaders().getFirst("X-Images-Exported"));
    com.fasterxml.jackson.databind.JsonNode manifest = null;
    Set<String> names = new HashSet<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
        if (entry.getName().equals("manifest.json")) manifest = mapper.readTree(zip.readAllBytes());
      }
    }
    assertNotNull(manifest);
    int rawOnly = 0;
    for (var image : manifest) {
      assertTrue(names.contains(image.path("rawFile").asText()));
      if (image.path("status").asText().equals("RAW_ONLY")) {
        rawOnly++; assertTrue(image.hasNonNull("diagnostic")); assertFalse(image.has("file"));
      }
    }
    assertEquals(1, rawOnly);
  }

}
