package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AiRetrieval {
  private final AiRepository repository;
  private final AiModelClient models;
  private final AiSettings settings;
  private final ElasticsearchIndex index;

  public AiRetrieval(AiRepository repository, AiModelClient models, AiSettings settings, ElasticsearchIndex index) {
    this.repository = repository; this.models = models; this.settings = settings; this.index = index;
  }

  public boolean ready(String id, int chunks) {
    var document = repository.document(id);
    return document.status().equals("READY") && document.version().equals(settings.indexVersion())
        && index.ready(repository.owner(), id, repository.indexGeneration(id), settings.indexVersion(), chunks);
  }

  public void index(String id, List<Chunk> chunks, Consumer<String> progress) throws IOException {
    String token = UUID.randomUUID().toString();
    boolean complete = false;
    try {
      if (!repository.claimIndex(id, token)) throw new ApiException(409, "INDEX_BUSY", "文档索引正在构建或清理");
      index.ensureIndex();
      for (int start = 0; start < chunks.size(); start += 8) {
        renew(id, token);
        var batch = chunks.subList(start, Math.min(start + 8, chunks.size()));
        var vectors = models.embed(batch.stream().map(Chunk::text).toList());
        renew(id, token);
        index.write(repository.owner(), id, token, settings.indexVersion(), batch, vectors);
        progress.accept("建立检索索引 " + Math.min(start + 8, chunks.size()) + " / " + chunks.size());
      }
      renew(id, token);
      index.refresh();
      if (!index.ready(repository.owner(), id, token, settings.indexVersion(), chunks.size()))
        throw new ApiException(503, "SEARCH_INDEX_FAILED", "文档索引不完整，请稍后重试");
      if (!repository.completeIndex(id, token, settings.indexVersion())) throw lostLease();
      complete = true;
    } finally {
      cleanup(id, token, complete);
    }
  }

  private void cleanup(String id, String token, boolean complete) {
    // JDBC on a virtual thread can close its connection on interruption; release using a fresh connection.
    boolean interrupted = Thread.interrupted();
    try {
      try { repository.releaseIndex(id, token); }
      catch (org.springframework.dao.DataAccessException e) {
        if (!Thread.interrupted()) throw e;
        interrupted = true;
        repository.releaseIndex(id, token);
      }
      if (!complete) {
        try { index.delete(repository.owner(), id, token); }
        catch (RuntimeException e) { /* 未发布代次不参与查询；清理失败时保留数据。 */ }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void renew(String id, String token) {
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    if (!repository.renewIndex(id, token)) throw lostLease();
  }

  private ApiException lostLease() { return new ApiException(409, "INDEX_LEASE_LOST", "索引构建执行权已失效"); }

  public void delete(String id) { index.delete(repository.owner(), id, null); }

  public List<Chunk> search(String documentId, String question, List<Chunk> allowed) throws IOException {
    var document = repository.document(documentId);
    if (document.status().equals("DELETING")) throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
    if (allowed.isEmpty()) return List.of();
    String generation = repository.indexGeneration(documentId);
    if (generation == null || !document.status().equals("READY") || !document.version().equals(settings.indexVersion()))
      throw new ApiException(409, "INDEX_NOT_READY", "文档索引尚未就绪");
    var vectors = models.embed(List.of(question));
    if (vectors.size() != 1) throw new ApiException(503, "AI_EMBEDDING_INVALID", "嵌入向量格式不正确");
    var ranks = new HashMap<String, Double>();
    var byId = new HashMap<String, Chunk>();
    allowed.forEach(c -> byId.put(c.id(), c));
    for (var route : index.ranks(repository.owner(), documentId, generation, settings.indexVersion(), question, vectors.getFirst(), allowed))
      for (int i = 0; i < route.size(); i++) {
        String id = route.get(i);
        if (!byId.containsKey(id)) throw new ApiException(503, "SEARCH_RESULT_INVALID", "检索结果范围不正确");
        ranks.merge(id, 1.0 / (60 + i + 1), Double::sum);
      }
    var candidates = ranks.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
        .limit(30).map(e -> byId.get(e.getKey())).toList();
    return models.rerank(question, candidates.stream().map(Chunk::text).toList()).stream().map(candidates::get).toList();
  }
}
