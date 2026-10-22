package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiRepository {
  private final AiSettings settings;
  private final ObjectMapper json;
  private final JdbcTemplate database;
  private final cn.edu.pdfworkbench.storage.DocumentStorage files;
  private final org.springframework.transaction.support.TransactionTemplate transactions;
  private final ThreadLocal<String> owner = new ThreadLocal<>();
  private final ThreadLocal<java.util.function.BooleanSupplier> access =
      ThreadLocal.withInitial(() -> () -> true);

  public String owner() {
    var value = owner.get();
    if (value == null) throw new ApiException(503, "CHAT_SPACE_REQUIRED", "聊天空间未初始化");
    return value;
  }

  public java.util.function.BooleanSupplier access() {
    return access.get();
  }

  public Path root() {
    return settings.root().resolve("users").resolve(owner());
  }

  public Scope scope(String value, java.util.function.BooleanSupplier guard) {
    validateId(value);
    var scope = new Scope(owner.get(), access());
    owner.set(value);
    access.set(guard);
    return scope;
  }

  public final class Scope implements AutoCloseable {
    private final String previous;
    private final java.util.function.BooleanSupplier guard;

    private Scope(String previous, java.util.function.BooleanSupplier guard) {
      this.previous = previous;
      this.guard = guard;
    }

    public void close() {
      if (previous == null) {
        owner.remove();
        access.remove();
      } else {
        owner.set(previous);
        access.set(guard);
      }
    }
  }

  public AiRepository(AiSettings settings, ObjectMapper json, JdbcTemplate database,
      cn.edu.pdfworkbench.storage.DocumentStorage files) {
    this.settings = settings;
    this.json = json;
    this.database = database;
    this.files = files;
    this.transactions = new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(database.getDataSource())));
  }

  public Path legacyDocumentPath(String id) {
    validateId(id);
    return root().resolve(id + ".pdf");
  }

  public Path legacyContentPath(String id) {
    validateId(id);
    return root().resolve(id + ".json");
  }

  public Path legacyIndexPath(String id) {
    validateId(id);
    return root().resolve("indexes").resolve(id);
  }

  public static void validateId(String id) {
    if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
      throw new ApiException(400, "INVALID_ID", "标识格式不正确");
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法保存 AI 数据", e);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new ApiException(500, "AI_STORAGE_ERROR", "本机数据无法读取");
    }
  }

  public void save(Document value) {
    database.update(
        "INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,?::jsonb)"
            + " ON CONFLICT(workspace_id,id) DO UPDATE SET payload=excluded.payload",
        owner(), value.id(), write(value));
  }

  public void save(Conversation value) {
    database.update(
            "INSERT INTO ai_conversations(workspace_id,id,doc_id,updated,payload)"
                + " VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb) ON CONFLICT(workspace_id,id)"
                + " DO UPDATE SET doc_id=excluded.doc_id,updated=excluded.updated,payload=excluded.payload",
            owner(), value.id(),
            value.documentId(),
            value.updatedAt(),
            write(value));
  }

  public void save(Message value) {
    database.update(
            "INSERT INTO ai_messages(workspace_id,id,conversation_id,created,payload)"
                + " VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb) ON CONFLICT(workspace_id,id)"
                + " DO UPDATE SET conversation_id=excluded.conversation_id,created=excluded.created,payload=excluded.payload",
            owner(), value.id(),
            value.conversationId(),
            value.createdAt(),
            write(value));
  }

  public Document document(String id) {
    validateId(id);
    return one(
        database.query(
                "SELECT payload FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid",
                (r, n) -> read(r.getString(1), Document.class),
                owner(), id));
  }

  public Conversation conversation(String id) {
    validateId(id);
    return one(
        database.query(
                "SELECT payload FROM ai_conversations WHERE workspace_id=?::uuid AND id=?::uuid",
                (r, n) -> read(r.getString(1), Conversation.class),
                owner(), id));
  }

  public Message message(String id) {
    if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
      throw new ApiException(400, "INVALID_ID", "标识格式不正确");
    return one(
        database.query(
                "SELECT payload FROM ai_messages WHERE workspace_id=?::uuid AND id=?::uuid",
                (r, n) -> read(r.getString(1), Message.class),
                owner(), id));
  }

  private <T> T one(List<T> values) {
    if (values.isEmpty()) throw new ApiException(404, "NOT_FOUND", "记录不存在或已删除");
    return values.getFirst();
  }

  public List<Document> documents() {
    return database.query(
            "SELECT payload FROM ai_documents WHERE workspace_id=?::uuid",
            (r, n) -> read(r.getString(1), Document.class), owner());
  }

  public List<Conversation> conversations() {
    return database.query(
            "SELECT payload FROM ai_conversations WHERE workspace_id=?::uuid ORDER BY updated DESC,id",
            (r, n) -> read(r.getString(1), Conversation.class), owner());
  }

  public List<Message> messages(String id) {
    conversation(id);
    return database.query(
            "SELECT payload FROM ai_messages WHERE workspace_id=?::uuid AND conversation_id=?::uuid ORDER BY created,id",
            (r, n) -> read(r.getString(1), Message.class),
            owner(), id);
  }

  public void publishDocument(Document document, Path pdf, Content value) throws IOException {
    requireAccess();
    Files.createDirectories(root());
    var temp = Files.createTempFile(root(), "content-", ".tmp");
    cn.edu.pdfworkbench.storage.DocumentStorage.Manifest manifest = null;
    try {
      json.writeValue(temp.toFile(), value);
      manifest = files.stage(owner(),document.id(),document.hash(),pdf,temp);
      files.upload(manifest,pdf,temp);
      requireAccess();
      files.publish(manifest,()->{ requireAccess(); save(document); });
    } catch (IOException | RuntimeException e) {
      if (manifest!=null) {
        try { files.abandon(manifest); }
        catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
      }
      throw e;
    } finally { Files.deleteIfExists(temp); }
  }

  public Content content(String id) throws IOException {
    requireAccess();
    byte[] bytes = files.content(owner(),id);
    requireAccess();
    try {
      var value=json.readValue(bytes, Content.class);
      if (value==null || value.chunks()==null || value.chapters()==null)
        throw new ApiException(502,"CONTENT_INVALID","持久文档内容无法读取");
      return value;
    }
    catch (JsonProcessingException e) { throw new ApiException(502,"CONTENT_INVALID","持久文档内容无法读取"); }
  }

  public void pdf(String id, Path destination) throws IOException {
    requireAccess();
    files.pdf(owner(),id,destination);
    try { requireAccess(); }
    catch (RuntimeException e) { Files.deleteIfExists(destination); throw e; }
  }

  public boolean filesReady(String id) {
    requireAccess();
    try { files.ready(owner(),id); return true; }
    catch (ApiException e) {
      if (e.status()==404 || e.code().equals("FILES_NOT_PUBLISHED") || e.code().equals("DOCUMENT_DELETING")) return false;
      throw e;
    }
  }

  public void requireAccess() {
    owner();
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    if (!access().getAsBoolean()) throw new ApiException(403,"CHAT_ACCESS_REVOKED","聊天空间访问已失效");
  }

  public void deleteConversation(String id) {
    conversation(id);
    database.update("DELETE FROM ai_conversations WHERE workspace_id=?::uuid AND id=?::uuid", owner(), id);
  }

  public String indexGeneration(String id) {
    validateId(id);
    return one(database.query(
        "SELECT index_generation::text FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid",
        (row, n) -> row.getString(1), owner(), id));
  }

  public boolean claimIndex(String id, String token) {
    validateId(id);
    validateId(token);
    return database.update(
        "UPDATE ai_documents SET index_build_token=?::uuid,index_lease_until=clock_timestamp()+interval '10 minutes'"
            + " WHERE workspace_id=?::uuid AND id=?::uuid AND payload->>'status'<>'DELETING'"
            + " AND (index_build_token IS NULL OR index_lease_until<clock_timestamp())",
        token, owner(), id) == 1;
  }

  public boolean renewIndex(String id, String token) {
    return database.update(
        "UPDATE ai_documents SET index_lease_until=clock_timestamp()+interval '10 minutes'"
            + " WHERE workspace_id=?::uuid AND id=?::uuid AND index_build_token=?::uuid"
            + " AND index_lease_until>clock_timestamp() AND payload->>'status'<>'DELETING'",
        owner(), id, token) == 1;
  }

  public boolean completeIndex(String id, String token, String version) {
    return database.update(
        "UPDATE ai_documents SET index_generation=?::uuid,index_build_token=NULL,index_lease_until=NULL,"
            + " payload=jsonb_set(jsonb_set(payload,'{status}','\"READY\"'),'{version}',to_jsonb(?::text))"
            + " WHERE workspace_id=?::uuid AND id=?::uuid AND index_build_token=?::uuid"
            + " AND index_lease_until>clock_timestamp() AND payload->>'status'<>'DELETING'",
        token, version, owner(), id, token) == 1;
  }

  public void releaseIndex(String id, String token) {
    database.update(
        "UPDATE ai_documents SET index_build_token=NULL,index_lease_until=NULL"
            + " WHERE workspace_id=?::uuid AND id=?::uuid AND index_build_token=?::uuid",
        owner(), id, token);
  }

  private Document lockDocument(String id) {
    validateId(id);
    return one(database.query(
        "SELECT payload FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid FOR UPDATE",
        (row, n) -> read(row.getString(1), Document.class), owner(), id));
  }

  public Conversation createConversation(Conversation value) {
    return transactions.execute(status -> {
      if (lockDocument(value.documentId()).status().equals("DELETING"))
        throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
      save(value);
      return value;
    });
  }

  public void markDeleting(String id) {
    transactions.executeWithoutResult(status -> {
      lockDocument(id);
      if (database.queryForObject("SELECT COUNT(*) FROM ai_conversations WHERE workspace_id=?::uuid AND doc_id=?::uuid",
          Integer.class, owner(), id) != 0) throw new ApiException(409, "DOCUMENT_IN_USE", "文档仍有关联会话");
      database.update(
          "UPDATE ai_documents SET index_build_token=NULL,index_lease_until=NULL,"
              + " payload=jsonb_set(payload,'{status}','\"DELETING\"') WHERE workspace_id=?::uuid AND id=?::uuid",
          owner(), id);
    });
  }

  public void deleteDocumentRecord(String id) {
    validateId(id);
    database.update("DELETE FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid", owner(), id);
  }
}
