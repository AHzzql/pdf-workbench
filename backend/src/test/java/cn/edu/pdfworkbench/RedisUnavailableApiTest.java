package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisUnavailableApiTest {
  static final TestServices SERVICES = new TestServices();
  static final String COOKIE = "PDF_WORKBENCH_ACCOUNT=" + Base64.getEncoder().encodeToString(
      "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8));

  @AfterAll
  static void closeServices() { SERVICES.close(); }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    SERVICES.register(properties);
    properties.add("spring.data.redis.port", () -> 9);
    properties.add("search.url", () -> "http://127.0.0.1:9");
  }

  @Autowired TestRestTemplate http;

  @Test
  void corePdfAndOpenApiIgnoreSessionCookieWhenRedisIsUnavailable() throws Exception {
    byte[] sample;
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.save(output);
      sample = output.toByteArray();
    }
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", new ByteArrayResource(sample) {
      @Override public String getFilename() { return "sample.pdf"; }
    });
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.add(HttpHeaders.COOKIE, COOKIE);
    var response = http.postForEntity("/api/v1/pdf/info", new HttpEntity<>(body, headers), String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"pageCount\":1"));
    assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    var documentHeaders = new HttpHeaders();
    documentHeaders.add(HttpHeaders.COOKIE, COOKIE);
    var docs = http.exchange("/v3/api-docs", HttpMethod.GET, new HttpEntity<>(documentHeaders), String.class);
    assertEquals(200, docs.getStatusCode().value());
  }

  @Test
  void statefulRoutesFailClosedWithoutLeakingStorageDetails() {
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, COOKIE);
    for (String route : new String[] {"/api/v1/account/session", "/api/v1/account/csrf", "/api/v1/pdf/ai/conversations"}) {
      var response = http.exchange(route, HttpMethod.GET, new HttpEntity<>(headers), String.class);
      assertEquals(503, response.getStatusCode().value(), route);
      assertEquals("no-store", response.getHeaders().getCacheControl());
      assertTrue(response.getBody().contains("SESSION_STORAGE_UNAVAILABLE"), route);
      assertFalse(response.getBody().contains("RedisConnection"));
    }
  }
}
