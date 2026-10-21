package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.AiTypes.Chunk;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;

class ElasticsearchIndexProtocolTest {
  @Test
  void rejectsPartialBulkFailuresDespiteHttpSuccess() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      assertTrue(exchange.getRequestURI().getQuery().contains("require_alias=true"));
      assertTrue(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).endsWith("\n"));
      var response = "{\"errors\":true,\"items\":[{\"index\":{\"status\":400,\"error\":{\"reason\":\"internal-fixture\"}}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (var output = exchange.getResponseBody()) { output.write(response); }
    });
    server.start();
    try {
      var index = new ElasticsearchIndex(new ElasticsearchSettings("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "not-a-secret", "pdf-workbench-protocol"), new ObjectMapper());
      var vector = new float[1024]; vector[0] = 1;
      var failure = assertThrows(ApiException.class, () -> index.write(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "version", List.of(new Chunk("p1-0", 1, 0, 4, "text", List.of())), List.of(vector)));
      assertEquals("SEARCH_INDEX_FAILED", failure.code());
      assertFalse(failure.getMessage().contains("internal-fixture"));
    } finally { server.stop(0); }
  }

  @Test
  void rejectsMalformedVectorsAndUnsafeConfigurationBeforeNetworkAccess() {
    for (var vector : List.of(new float[3], new float[1024]))
      assertEquals("AI_EMBEDDING_INVALID", assertThrows(ApiException.class, () -> ElasticsearchIndex.validateVector(vector)).code());
    for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      var vector = new float[1024]; vector[0] = value;
      assertThrows(ApiException.class, () -> ElasticsearchIndex.validateVector(vector));
    }
    assertThrows(IllegalArgumentException.class, () -> new ElasticsearchSettings("http://name:password@localhost:9200", "u", "p", "pdf-workbench-test"));
    assertThrows(IllegalArgumentException.class, () -> new ElasticsearchSettings("http://localhost:9200", "u", "p", "../../invalid"));
  }
}
