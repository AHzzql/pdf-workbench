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

  public AiRepository(AiSettings settings, ObjectMapper json, JdbcTemplate database) {
    this.settings = settings;
    this.json = json;
    this.database = database;
  }

  public Path documentPath(String id) {
    validateId(id);
    return root().resolve(id + ".pdf");
  }

  public Path contentPath(String id) {
    validateId(id);
    return root().resolve(id + ".json");
  }

  public Path indexPath(String id) {
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

  public synchronized void save(Document value) {
    database.update(
        "INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,?::jsonb)"
            + " ON CONFLICT(workspace_id,id) DO UPDATE SET payload=excluded.payload",
        owner(), value.id(), write(value));
  }

  public synchronized void save(Conversation value) {
    database.update(
            "INSERT INTO ai_conversations(workspace_id,id,doc_id,updated,payload)"
                + " VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb) ON CONFLICT(workspace_id,id)"
                + " DO UPDATE SET doc_id=excluded.doc_id,updated=excluded.updated,payload=excluded.payload",
            owner(), value.id(),
            value.documentId(),
            value.updatedAt(),
            write(value));
  }

  public synchronized void save(Message value) {
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

  public void content(String id, Content value) throws IOException {
    Files.createDirectories(root());
    var target = contentPath(id);
    var temp = Files.createTempFile(root(), "content-", ".tmp");
    try {
      json.writeValue(temp.toFile(), value);
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public Content content(String id) throws IOException {
    return json.readValue(contentPath(id).toFile(), Content.class);
  }

  public synchronized void deleteConversation(String id) {
    conversation(id);
    database.update("DELETE FROM ai_conversations WHERE workspace_id=?::uuid AND id=?::uuid", owner(), id);
  }

  public void deleteDocumentRecord(String id) {
    validateId(id);
    database.update("DELETE FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid", owner(), id);
  }
}
