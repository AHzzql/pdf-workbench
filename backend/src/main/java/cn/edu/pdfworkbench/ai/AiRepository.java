package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

@Repository
public class AiRepository {
  private final AiSettings settings;
  private final ObjectMapper json;
  private JdbcTemplate db;

  public AiRepository(AiSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  private synchronized JdbcTemplate db() {
    if (db != null) return db;
    try {
      Files.createDirectories(settings.root());
    } catch (IOException e) {
      throw new ApiException(500, "AI_STORAGE_ERROR", "无法创建本机数据目录");
    }
    var data =
        new DriverManagerDataSource(
            "jdbc:h2:file:"
                + settings.root().resolve("history").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "");
    db = new JdbcTemplate(data);
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_documents(id VARCHAR(64) PRIMARY KEY, payload CLOB NOT"
            + " NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_conversations(id VARCHAR(64) PRIMARY KEY, doc_id VARCHAR(64)"
            + " NOT NULL, updated BIGINT NOT NULL, payload CLOB NOT NULL)");
    db.execute(
        "CREATE TABLE IF NOT EXISTS ai_messages(id VARCHAR(100) PRIMARY KEY, conversation_id"
            + " VARCHAR(64) NOT NULL, created BIGINT NOT NULL, payload CLOB NOT NULL)");
    db.execute(
        "CREATE INDEX IF NOT EXISTS ai_messages_conversation ON ai_messages(conversation_id,"
            + " created)");
    for (var m :
        db.query(
            "SELECT payload FROM ai_messages", (r, n) -> read(r.getString(1), Message.class))) {
      if (m.status().equals("RUNNING"))
        save(
            new Message(
                m.id(),
                m.conversationId(),
                m.role(),
                m.text(),
                "STOPPED",
                "服务重启，生成已中断",
                m.mode(),
                m.question(),
                m.pages(),
                m.chapterId(),
                m.citations(),
                m.createdAt(),
                m.sequence() + 1));
    }
    return db;
  }

  public Path documentPath(String id) {
    validateId(id);
    return settings.root().resolve(id + ".pdf");
  }

  public Path contentPath(String id) {
    validateId(id);
    return settings.root().resolve(id + ".json");
  }

  public Path indexPath(String id) {
    validateId(id);
    return settings.root().resolve("indexes").resolve(id);
  }

  public static void validateId(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}"))
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
    db().update("MERGE INTO ai_documents KEY(id) VALUES(?,?)", value.id(), write(value));
  }

  public synchronized void save(Conversation value) {
    db().update(
            "MERGE INTO ai_conversations KEY(id) VALUES(?,?,?,?)",
            value.id(),
            value.documentId(),
            value.updatedAt(),
            write(value));
  }

  public synchronized void save(Message value) {
    db().update(
            "MERGE INTO ai_messages KEY(id) VALUES(?,?,?,?)",
            value.id(),
            value.conversationId(),
            value.createdAt(),
            write(value));
  }

  public Document document(String id) {
    validateId(id);
    return one(
        db().query(
                "SELECT payload FROM ai_documents WHERE id=?",
                (r, n) -> read(r.getString(1), Document.class),
                id));
  }

  public Conversation conversation(String id) {
    validateId(id);
    return one(
        db().query(
                "SELECT payload FROM ai_conversations WHERE id=?",
                (r, n) -> read(r.getString(1), Conversation.class),
                id));
  }

  public Message message(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}"))
      throw new ApiException(400, "INVALID_ID", "标识格式不正确");
    return one(
        db().query(
                "SELECT payload FROM ai_messages WHERE id=?",
                (r, n) -> read(r.getString(1), Message.class),
                id));
  }

  private <T> T one(List<T> values) {
    if (values.isEmpty()) throw new ApiException(404, "NOT_FOUND", "记录不存在或已删除");
    return values.getFirst();
  }

  public List<Document> documents() {
    return db().query(
            "SELECT payload FROM ai_documents", (r, n) -> read(r.getString(1), Document.class));
  }

  public List<Conversation> conversations() {
    return db().query(
            "SELECT payload FROM ai_conversations ORDER BY updated DESC",
            (r, n) -> read(r.getString(1), Conversation.class));
  }

  public List<Message> messages(String id) {
    conversation(id);
    return db().query(
            "SELECT payload FROM ai_messages WHERE conversation_id=? ORDER BY created,id",
            (r, n) -> read(r.getString(1), Message.class),
            id);
  }

  public void content(String id, Content value) throws IOException {
    var target = contentPath(id);
    var temp = Files.createTempFile(settings.root(), "content-", ".tmp");
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
    db().update("DELETE FROM ai_messages WHERE conversation_id=?", id);
    db().update("DELETE FROM ai_conversations WHERE id=?", id);
  }

  public void deleteDocumentRecord(String id) {
    db().update("DELETE FROM ai_documents WHERE id=?", id);
  }
}
