package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTest {
  @Autowired private TestRestTemplate client;

  @Test
  void servesOpenApiFromRunningServer() {
    var response = client.getForEntity("/v3/api-docs", String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"openapi\""));
  }
}
