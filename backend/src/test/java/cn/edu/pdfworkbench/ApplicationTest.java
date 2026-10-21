package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationTest {
  static final TestServices SERVICES = new TestServices();

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry r) { SERVICES.register(r); }

  @org.junit.jupiter.api.AfterAll
  static void closeServices() { SERVICES.close(); }

  @Autowired private TestRestTemplate client;

  @Test
  void servesOpenApiFromRunningServer() {
    var response = client.getForEntity("/v3/api-docs", String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"openapi\""));
  }
}
