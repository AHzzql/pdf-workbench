package cn.edu.pdfworkbench.tasks;

import cn.edu.pdfworkbench.ai.AiRepository;
import cn.edu.pdfworkbench.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class TaskStore {
  public enum Kind {
    GENERATE("reading"), INDEX("index"), CLEAN("cleanup");
    public final String route;
    Kind(String route) { this.route = route; }
  }
  public record Task(String id, String workspace, Kind kind, String target, String document,
      String conversation, String state, int attempt, long fence, long version, String stage) {
    public boolean terminal() { return Set.of("SUCCEEDED","FAILED","CANCELLED").contains(state); }
  }
  public record Event(long id, String task, long version, String route, String lease, int attempts) {}
  public static class LeaseLost extends RuntimeException {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TaskSettings settings;

  public TaskStore(JdbcTemplate jdbc, TaskSettings settings) {
    this.jdbc = jdbc;
    this.settings = settings;
    transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  private Task row(ResultSet r, int n) throws SQLException {
    return new Task(r.getString("id"), r.getString("workspace_id"), Kind.valueOf(r.getString("kind")),
        r.getString("target_id"), r.getString("document_id"), r.getString("conversation_id"),
        r.getString("state"), r.getInt("attempt"), r.getLong("fence"), r.getLong("dispatch_version"), r.getString("stage"));
  }

  public <T> T submission(Supplier<T> work) {
    return transactions.execute(status -> {
      jdbc.execute("SET LOCAL lock_timeout='3s'");
      jdbc.queryForObject("SELECT pg_advisory_xact_lock(736281940)", Object.class);
      return work.get();
    });
  }

  public Task enqueue(String workspace, Kind kind, String target, String document, String conversation) {
    AiRepository.validateId(workspace); AiRepository.validateId(target); AiRepository.validateId(document);
    if (conversation != null) AiRepository.validateId(conversation);
    return submission(() -> {
      var previous = jdbc.query("SELECT * FROM work_tasks WHERE workspace_id=?::uuid AND kind=? AND target_id=?::uuid"
          + (kind == Kind.GENERATE ? "" : " AND state IN ('QUEUED','RUNNING','RETRY')"), this::row, workspace, kind.name(), target);
      if (!previous.isEmpty()) return previous.getFirst();
      if (jdbc.queryForObject("SELECT COUNT(*) FROM work_tasks WHERE kind=? AND state IN ('QUEUED','RUNNING','RETRY')", Integer.class, kind.name()) >= settings.maxPending()
          || jdbc.queryForObject("SELECT COUNT(*) FROM task_outbox WHERE published_at IS NULL", Integer.class) >= settings.maxPending()*3*(settings.maxAttempts()+1))
        throw new ApiException(429, "TASK_CAPACITY", "任务队列已满，请稍后重试");
      if (kind == Kind.GENERATE && (busy(workspace, conversation) || jdbc.queryForObject(
          "SELECT COUNT(*) FROM work_tasks WHERE workspace_id=?::uuid AND kind='GENERATE' AND state IN ('QUEUED','RUNNING','RETRY')", Integer.class, workspace) >= 2))
        throw new ApiException(429, "AI_BUSY", "生成任务已满，或当前会话正在生成");
      String id = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO work_tasks(id,workspace_id,kind,target_id,document_id,conversation_id,state) VALUES(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,'QUEUED')",
          id, workspace, kind.name(), target, document, conversation);
      event(id, 1, kind.route);
      return get(id);
    });
  }

  public Task get(String id) {
    return jdbc.query("SELECT * FROM work_tasks WHERE id=?::uuid", this::row, id).stream().findFirst().orElse(null);
  }

  public Task forMessage(String workspace, String target) {
    return jdbc.query("SELECT * FROM work_tasks WHERE workspace_id=?::uuid AND kind='GENERATE' AND target_id=?::uuid",
        this::row, workspace, target).stream().findFirst().orElse(null);
  }

  public boolean busy(String workspace, String conversation) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM work_tasks WHERE workspace_id=?::uuid AND conversation_id=?::uuid"
        + " AND kind='GENERATE' AND state IN ('QUEUED','RUNNING','RETRY'))", Boolean.class, workspace, conversation));
  }

  public Task claim(String id, long version, String route) {
    return transactions.execute(status -> {
      var candidates = jdbc.query("SELECT * FROM work_tasks WHERE id=?::uuid AND dispatch_version=? AND state IN ('QUEUED','RETRY')"
          + " AND available_at<=clock_timestamp() AND (deadline IS NULL OR deadline>clock_timestamp()) FOR UPDATE SKIP LOCKED", this::row, id, version);
      if (candidates.isEmpty()) return null;
      var task = candidates.getFirst();
      if (!task.kind().route.equals(route)) return null;
      jdbc.update("UPDATE work_tasks SET state='RUNNING',attempt=attempt+1,fence=fence+1,lease_until=clock_timestamp()+make_interval(secs=>?),"
          + " deadline=COALESCE(deadline,clock_timestamp()+make_interval(secs=>?)),stage='处理中',updated_at=clock_timestamp() WHERE id=?::uuid",
          settings.leaseSeconds(), settings.timeoutSeconds(), id);
      return get(id);
    });
  }

  public boolean heartbeat(Task task) {
    return jdbc.update("UPDATE work_tasks SET lease_until=LEAST(deadline,clock_timestamp()+make_interval(secs=>?)),updated_at=clock_timestamp()"
        + " WHERE id=?::uuid AND fence=? AND state='RUNNING' AND lease_until>clock_timestamp() AND deadline>clock_timestamp()",
        settings.leaseSeconds(), task.id(), task.fence()) == 1;
  }

  public boolean active(Task task) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM work_tasks WHERE id=?::uuid AND fence=? AND state='RUNNING'"
        + " AND lease_until>clock_timestamp() AND deadline>clock_timestamp())", Boolean.class, task.id(), task.fence()));
  }

  public <T> T fenced(Task task, Supplier<T> work) {
    return transactions.execute(status -> {
      if (jdbc.query("SELECT id FROM work_tasks WHERE id=?::uuid AND fence=? AND state='RUNNING' AND lease_until>clock_timestamp()"
          + " AND deadline>clock_timestamp() FOR UPDATE", (r,n) -> r.getString(1), task.id(), task.fence()).isEmpty()) throw new LeaseLost();
      return work.get();
    });
  }

  public void stage(Task task, String stage) {
    fenced(task, () -> { jdbc.update("UPDATE work_tasks SET stage=? WHERE id=?::uuid", stage.substring(0, Math.min(stage.length(), 96)), task.id()); return null; });
  }

  public void succeed(Task task, Runnable result) {
    fenced(task, () -> {
      result.run();
      jdbc.update("UPDATE work_tasks SET state='SUCCEEDED',lease_until=NULL,stage='已完成',updated_at=clock_timestamp() WHERE id=?::uuid", task.id());
      return null;
    });
  }

  public void fail(Task task, String code, String message, boolean retryable) {
    fenced(task, () -> { transition(get(task.id()), code, message, retryable); return null; });
  }

  private void transition(Task task, String code, String message, boolean retryable) {
    boolean expired = Boolean.TRUE.equals(jdbc.queryForObject("SELECT deadline<=clock_timestamp() FROM work_tasks WHERE id=?::uuid", Boolean.class, task.id()));
    boolean retry = retryable && !expired && task.attempt() < settings.maxAttempts();
    long version = task.version() + (retry ? 1 : 0);
    jdbc.update("UPDATE work_tasks SET state=?,lease_until=NULL,dispatch_version=?,available_at=clock_timestamp()+make_interval(secs=>?),"
        + " stage=?,error_code=?,updated_at=clock_timestamp() WHERE id=?::uuid",
        retry ? "RETRY" : "FAILED", version, retry ? Math.min(30, 1 << Math.min(task.attempt(), 5)) : 0,
        retry ? "等待重试" : "任务失败", code, task.id());
    messageStatus(task, retry ? "RUNNING" : "FAILED", retry ? "正在重试" : message, retry);
    event(task.id(), version, retry ? task.kind().route : "dead");
  }

  private void messageStatus(Task task, String state, String error, boolean reset) {
    if (task.kind() != Kind.GENERATE) return;
    jdbc.update("UPDATE ai_messages SET payload=payload || jsonb_build_object('status',?::text,'error',?::text,'sequence',"
        + " (payload->>'sequence')::bigint+1,'text',CASE WHEN ? THEN '' ELSE payload->>'text' END,'citations','[]'::jsonb)"
        + " WHERE workspace_id=?::uuid AND id=?::uuid", state, error, reset, task.workspace(), task.target());
  }

  public void cancelMessage(String workspace, String target) {
    submission(() -> {
      var tasks = jdbc.query("SELECT * FROM work_tasks WHERE workspace_id=?::uuid AND kind='GENERATE' AND target_id=?::uuid FOR UPDATE", this::row, workspace, target);
      if (!tasks.isEmpty()) cancel(tasks.getFirst());
      return null;
    });
  }

  private void cancel(Task task) {
    if (task.terminal()) return;
    jdbc.update("UPDATE work_tasks SET state='CANCELLED',fence=fence+1,lease_until=NULL,stage='已停止',updated_at=clock_timestamp() WHERE id=?::uuid", task.id());
    messageStatus(task, "STOPPED", "", false);
  }

  public void cancelDocument(String workspace, String document) {
    jdbc.query("SELECT * FROM work_tasks WHERE workspace_id=?::uuid AND document_id=?::uuid AND kind<>'CLEAN'"
        + " AND state IN ('QUEUED','RUNNING','RETRY') FOR UPDATE", this::row, workspace, document).forEach(this::cancel);
  }

  public int recoverExpired() {
    return transactions.execute(status -> {
      var tasks = jdbc.query("SELECT * FROM work_tasks WHERE (state='RUNNING' AND lease_until<=clock_timestamp())"
          + " OR (state IN ('QUEUED','RETRY') AND deadline<=clock_timestamp()) ORDER BY updated_at LIMIT 32 FOR UPDATE SKIP LOCKED", this::row);
      for (var task : tasks) transition(task, "TASK_LEASE_EXPIRED", "任务执行中断或超时", true);
      return tasks.size();
    });
  }

  public int recoverDeliveries() {
    return jdbc.update("WITH missing AS (SELECT e.id FROM task_outbox e JOIN work_tasks t ON t.id=e.task_id"
        + " WHERE t.state IN ('QUEUED','RETRY') AND t.dispatch_version=e.dispatch_version AND e.route<>'dead'"
        + " AND e.published_at<clock_timestamp()-interval '60 seconds' AND t.available_at<=clock_timestamp()"
        + " ORDER BY e.id LIMIT 32 FOR UPDATE OF e SKIP LOCKED)"
        + " UPDATE task_outbox SET published_at=NULL,available_at=clock_timestamp() WHERE id IN (SELECT id FROM missing)");
  }

  public int reconcileCleanup() {
    return submission(() -> {
      int pending=jdbc.queryForObject("SELECT COUNT(*) FROM work_tasks WHERE kind='CLEAN' AND state IN ('QUEUED','RUNNING','RETRY')",Integer.class);
      int outbox=jdbc.queryForObject("SELECT COUNT(*) FROM task_outbox WHERE published_at IS NULL",Integer.class);
      int limit=Math.min(8,Math.min(settings.maxPending()-pending,settings.maxPending()*3*(settings.maxAttempts()+1)-outbox));
      if(limit<=0)return 0;
      var candidates=jdbc.query("SELECT t.* FROM work_tasks t WHERE t.kind='CLEAN' AND t.state='FAILED'"
          + " AND t.updated_at<clock_timestamp()-interval '15 minutes'"
          + " AND EXISTS(SELECT 1 FROM ai_documents d WHERE d.workspace_id=t.workspace_id AND d.id=t.document_id AND d.payload->>'status'='DELETING')"
          + " AND NOT EXISTS(SELECT 1 FROM ai_conversations c WHERE c.workspace_id=t.workspace_id AND c.doc_id=t.document_id)"
          + " AND NOT EXISTS(SELECT 1 FROM work_tasks a WHERE a.workspace_id=t.workspace_id AND a.kind='CLEAN' AND a.document_id=t.document_id"
          + " AND a.state IN ('QUEUED','RUNNING','RETRY')) ORDER BY t.updated_at,t.id LIMIT ? FOR UPDATE OF t SKIP LOCKED",this::row,limit);
      int recovered=0;
      for(var task:candidates) {
        // Match execution's task-before-document lock order; a busy document waits for the next sweep.
        var documents=jdbc.query("SELECT id FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid AND payload->>'status'='DELETING'"
            + " FOR UPDATE SKIP LOCKED",(r,n)->r.getString(1),task.workspace(),task.document());
        if(documents.isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversations WHERE workspace_id=?::uuid AND doc_id=?::uuid",
            Integer.class,task.workspace(),task.document())!=0)continue;
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM work_tasks WHERE workspace_id=?::uuid AND kind='CLEAN'"
            + " AND document_id=?::uuid AND state IN ('QUEUED','RUNNING','RETRY'))",Boolean.class,task.workspace(),task.document())))continue;
        jdbc.update("UPDATE work_tasks SET state='QUEUED',attempt=0,fence=fence+1,dispatch_version=dispatch_version+1,deadline=NULL,lease_until=NULL,"
            + " available_at=clock_timestamp(),updated_at=clock_timestamp(),stage='等待清理',error_code='' WHERE id=?::uuid",task.id());
        event(task.id(),task.version()+1,Kind.CLEAN.route);
        recovered++;
      }
      return recovered;
    });
  }

  private void event(String task, long version, String route) {
    jdbc.update("INSERT INTO task_outbox(task_id,dispatch_version,route,available_at) SELECT id,?, ?,available_at FROM work_tasks WHERE id=?::uuid"
        + " ON CONFLICT DO NOTHING", version, route, task);
  }

  public Event nextEvent() {
    String lease = UUID.randomUUID().toString();
    return jdbc.query("WITH candidate AS (SELECT id FROM task_outbox WHERE published_at IS NULL AND available_at<=clock_timestamp()"
        + " AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)"
        + " UPDATE task_outbox SET lease_id=?::uuid,lease_until=clock_timestamp()+interval '15 seconds',attempts=attempts+1"
        + " WHERE id=(SELECT id FROM candidate) RETURNING *",
        (r,n) -> new Event(r.getLong("id"),r.getString("task_id"),r.getLong("dispatch_version"),r.getString("route"),r.getString("lease_id"),r.getInt("attempts")), lease).stream().findFirst().orElse(null);
  }

  public void published(Event event) {
    jdbc.update("UPDATE task_outbox SET published_at=clock_timestamp(),lease_id=NULL,lease_until=NULL WHERE id=? AND lease_id=?::uuid", event.id(), event.lease());
  }

  public void publishFailed(Event event) {
    jdbc.update("UPDATE task_outbox SET lease_id=NULL,lease_until=NULL,available_at=clock_timestamp()+make_interval(secs=>?) WHERE id=? AND lease_id=?::uuid",
        Math.min(30, 1 << Math.min(event.attempts(), 5)), event.id(), event.lease());
  }
}
