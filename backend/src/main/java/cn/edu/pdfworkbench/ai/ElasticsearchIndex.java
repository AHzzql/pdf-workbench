package cn.edu.pdfworkbench.ai;

import cn.edu.pdfworkbench.util.Identifiers;
import cn.edu.pdfworkbench.ai.AiTypes.Chunk;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchIndex {
  private final ElasticsearchSettings settings;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String authorization;
  private record Reply(int status, JsonNode body) {}

  public ElasticsearchIndex(ElasticsearchSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
    authorization = "Basic " + Base64.getEncoder().encodeToString(
        (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
  }

  private Reply request(String method, String path, String body, String contentType) {
    try {
      var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
      var request = HttpRequest.newBuilder(URI.create(settings.url() + path))
          .timeout(Duration.ofSeconds(30)).header("Authorization", authorization)
          .header("Content-Type", contentType).method(method, publisher).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        var bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
        if (bytes.length > 8 * 1024 * 1024) throw unavailable();
        return new Reply(response.statusCode(), bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException();
    } catch (IOException e) { throw unavailable(); }
  }

  private Reply request(String method, String path, Object body) {
    try { return request(method, path, body == null ? null : json.writeValueAsString(body), "application/json"); }
    catch (IOException e) { throw unavailable(); }
  }

  private JsonNode successful(Reply reply) {
    if (reply.status() < 200 || reply.status() >= 300) throw unavailable();
    return reply.body();
  }

  private boolean exists() {
    var reply = request("HEAD", "/_alias/" + settings.alias(), null);
    if (reply.status() == 404) return false;
    successful(reply);
    return true;
  }

  public void ensureIndex() {
    if (exists()) return;
    var fields = new LinkedHashMap<String, Object>();
    for (String field : List.of("workspace_id", "document_id", "generation", "index_version", "chunk_id"))
      fields.put(field, Map.of("type", "keyword"));
    for (String field : List.of("page", "start", "end")) fields.put(field, Map.of("type", "integer"));
    fields.put("text", Map.of("type", "text", "analyzer", "cjk"));
    fields.put("vector", Map.of("type", "dense_vector", "dims", 1024, "index", true, "similarity", "cosine"));
    var reply = request("PUT", "/" + settings.physicalIndex(), Map.of(
        "settings", Map.of("number_of_shards", 1, "number_of_replicas", 0),
        "mappings", Map.of("dynamic", "strict", "properties", fields),
        "aliases", Map.of(settings.alias(), Map.of("is_write_index", true))));
    if (reply.status() == 400 && reply.body().path("error").path("type").asText().equals("resource_already_exists_exception")
        && exists()) return;
    successful(reply);
  }

  public boolean ready(String workspace, String document, String generation, String version, int expected) {
    if (generation == null || !exists()) return false;
    var result = successful(request("POST", "/" + settings.alias() + "/_count",
        Map.of("query", filter(workspace, document, generation, version, null))));
    if (result.path("_shards").path("failed").asInt() != 0 || !result.has("count")) throw unavailable();
    return result.path("count").asInt() == expected;
  }

  public void write(String workspace, String document, String generation, String version,
      List<Chunk> chunks, List<float[]> vectors) {
    if (chunks.size() != vectors.size()) throw invalidEmbedding();
    var lines = new StringBuilder();
    try {
      for (int i = 0; i < chunks.size(); i++) {
        validateVector(vectors.get(i));
        var chunk = chunks.get(i);
        var key = workspace + ":" + document + ":" + generation + ":" + hash(chunk.id());
        lines.append(json.writeValueAsString(Map.of("index", Map.of("_id", key)))).append('\n');
        var value = new LinkedHashMap<String, Object>();
        value.put("workspace_id", workspace); value.put("document_id", document);
        value.put("generation", generation); value.put("index_version", version);
        value.put("chunk_id", chunk.id()); value.put("page", chunk.page());
        value.put("start", chunk.start()); value.put("end", chunk.end());
        value.put("text", chunk.text()); value.put("vector", vectors.get(i));
        lines.append(json.writeValueAsString(value)).append('\n');
      }
    } catch (IOException e) { throw unavailable(); }
    var result = successful(request("POST", "/" + settings.alias() + "/_bulk?require_alias=true",
        lines.toString(), "application/x-ndjson"));
    if (!result.has("errors") || result.path("errors").asBoolean() || !result.path("items").isArray()
        || result.path("items").size() != chunks.size()) throw indexFailed();
    for (var item : result.path("items")) {
      int status = item.path("index").path("status").asInt();
      if (status < 200 || status >= 300 || item.path("index").has("error")) throw indexFailed();
    }
  }

  public void refresh() {
    var result = successful(request("POST", "/" + settings.alias() + "/_refresh", null));
    if (result.path("_shards").path("failed").asInt() != 0) throw indexFailed();
  }

  public List<List<String>> ranks(String workspace, String document, String generation, String version,
      String question, float[] vector, List<Chunk> allowed) {
    validateVector(vector);
    var filter = filter(workspace, document, generation, version, allowed.stream().map(Chunk::id).toList());
    var lexical = Map.of("size", 20, "_source", List.of("chunk_id"), "track_total_hits", false,
        "query", Map.of("bool", Map.of("filter", List.of(filter),
            "must", List.of(Map.of("match", Map.of("text", question))))));
    var semantic = Map.of("size", 20, "_source", List.of("chunk_id"), "track_total_hits", false,
        "knn", Map.of("field", "vector", "query_vector", vector, "k", 20, "num_candidates", 100, "filter", filter));
    var output = new ArrayList<List<String>>();
    for (var query : List.of(lexical, semantic)) {
      var result = successful(request("POST", "/" + settings.alias() + "/_search", query));
      if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() != 0
          || !result.path("hits").path("hits").isArray()) throw unavailable();
      var ids = new ArrayList<String>();
      for (var hit : result.path("hits").path("hits")) {
        String id = hit.path("_source").path("chunk_id").asText();
        if (!ids.contains(id)) ids.add(id);
      }
      output.add(ids);
    }
    return output;
  }

  public void delete(String workspace, String document, String generation) {
    if (!exists()) return;
    var result = successful(request("POST", "/" + settings.alias() + "/_delete_by_query?refresh=true",
        Map.of("query", filter(workspace, document, generation, null, null))));
    if (result.path("timed_out").asBoolean() || result.path("version_conflicts").asInt() != 0
        || !result.path("failures").isArray() || !result.path("failures").isEmpty()) throw indexFailed();
  }

  private Map<String, Object> filter(String workspace, String document, String generation, String version, List<String> chunks) {
    Identifiers.requireUuid(workspace); Identifiers.requireUuid(document);
    var terms = new ArrayList<Object>();
    terms.add(Map.of("term", Map.of("workspace_id", workspace)));
    terms.add(Map.of("term", Map.of("document_id", document)));
    if (generation != null) {
      Identifiers.requireUuid(generation);
      terms.add(Map.of("term", Map.of("generation", generation)));
    }
    if (version != null) terms.add(Map.of("term", Map.of("index_version", version)));
    if (chunks != null) terms.add(Map.of("terms", Map.of("chunk_id", chunks)));
    return Map.of("bool", Map.of("filter", terms));
  }

  public static void validateVector(float[] vector) {
    if (vector == null || vector.length != 1024) throw invalidEmbedding();
    double norm = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) throw invalidEmbedding();
      norm += (double) value * value;
    }
    if (norm == 0) throw invalidEmbedding();
  }

  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private static ApiException invalidEmbedding() { return new ApiException(503, "AI_EMBEDDING_INVALID", "嵌入向量格式不正确"); }
  private ApiException unavailable() { return new ApiException(503, "SEARCH_UNAVAILABLE", "检索服务暂不可用，请稍后重试"); }
  private ApiException indexFailed() { return new ApiException(503, "SEARCH_INDEX_FAILED", "文档索引写入失败，请稍后重试"); }
}
