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
  @Test
  void validatesGenerationPaginationBeforeReturningCleanupCandidates() throws Exception {
    var mapper = new ObjectMapper();
    var key = Map.of("workspace", UUID.randomUUID().toString(), "document", UUID.randomUUID().toString(),
        "generation", UUID.randomUUID().toString());
    var body = new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
      exchange.getRequestBody().readAllBytes();
      byte[] response = mapper.writeValueAsBytes(body.get());
      exchange.sendResponseHeaders(200, response.length);
      try (var stream = exchange.getResponseBody()) { stream.write(response); }
    });
    server.start();
    try {
      var index = new ElasticsearchIndex(new ElasticsearchSettings("http://127.0.0.1:" + server.getAddress().getPort(),
          "fixture", "fixture", "pdf-workbench-protocol"), mapper);
      var aggregation = Map.of("buckets", List.of(Map.of("key", key)), "after_key", key);
      body.set(Map.of("aggregations", Map.of("generations", aggregation)));
      assertEquals(1, index.generations(Map.of(), 1).items().size());
      assertEquals(key, index.generations(Map.of(), 1).after());
      assertThrows(ApiException.class, () -> index.generations(key, 1));
      body.set(Map.of("timed_out", true, "aggregations", Map.of("generations", aggregation)));
      assertThrows(ApiException.class, () -> index.generations(Map.of(), 1));
      body.set(Map.of("aggregations", Map.of("generations", Map.of("buckets", List.of(Map.of("key", key))))));
      assertThrows(ApiException.class, () -> index.generations(Map.of(), 1));
      var invalid = new HashMap<>(key); invalid.put("generation", "not-a-uuid");
      body.set(Map.of("aggregations", Map.of("generations", Map.of("buckets", List.of(Map.of("key", invalid)), "after_key", key))));
      assertThrows(ApiException.class, () -> index.generations(Map.of(), 1));
      body.set(Map.of("_shards", Map.of("failed", 1), "aggregations", Map.of("generations", aggregation)));
      assertThrows(ApiException.class, () -> index.generations(Map.of(), 1));
    } finally { server.stop(0); }
  }

}
