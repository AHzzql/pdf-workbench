package cn.edu.pdfworkbench.ai;

import cn.edu.pdfworkbench.ai.ElasticsearchIndex.Generation;
import cn.edu.pdfworkbench.tasks.TaskSettings;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles disposable search generations against authoritative document pointers. */
@Component
public final class IndexMaintenance implements SmartLifecycle {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IndexMaintenance.class);
  private final JdbcTemplate database;
  private final ElasticsearchIndex index;
  private final TaskSettings tasks;
  private final int graceSeconds;
  private final TransactionTemplate transactions;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("index-generation-cleanup").daemon(true).factory());
  private Map<String, String> cursor = Map.of();
  private volatile boolean running;

  public IndexMaintenance(JdbcTemplate database, ElasticsearchIndex index, TaskSettings tasks,
      @Value("${search.cleanup-grace-seconds:600}") int graceSeconds) {
    if (graceSeconds < 60 || graceSeconds > 86400) throw new IllegalArgumentException("Invalid index cleanup grace");
    this.database = database;
    this.index = index;
    this.tasks = tasks;
    this.graceSeconds = graceSeconds;
    transactions = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
        Objects.requireNonNull(database.getDataSource())));
  }

  public synchronized void sweep() {
    var page = index.generations(cursor, 32);
    for (var generation : page.items()) transactions.executeWithoutResult(status -> {
      if (protectedOrLocked(generation)) {
        forget(generation);
      } else {
        database.update("INSERT INTO index_gc_candidates(workspace_id,document_id,generation)"
            + " VALUES(?::uuid,?::uuid,?::uuid) ON CONFLICT DO NOTHING",
            generation.workspace(), generation.document(), generation.generation());
      }
    });
    cursor = page.items().isEmpty() ? Map.of() : page.after();
    var due = database.query("SELECT workspace_id::text,document_id::text,generation::text"
        + " FROM index_gc_candidates WHERE observed_at < clock_timestamp()-make_interval(secs=>?)"
        + " ORDER BY observed_at,workspace_id,document_id,generation LIMIT 8",
        (row, n) -> new Generation(row.getString(1), row.getString(2), row.getString(3)), graceSeconds);
    for (var generation : due) transactions.executeWithoutResult(status -> {
      if (protectedOrLocked(generation)) return;
      var claimed = database.queryForList("SELECT 1 FROM index_gc_candidates"
          + " WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid"
          + " AND observed_at<clock_timestamp()-make_interval(secs=>?) FOR UPDATE SKIP LOCKED",
          Integer.class, generation.workspace(), generation.document(), generation.generation(), graceSeconds);
      if (claimed.isEmpty()) return;
      index.delete(generation.workspace(), generation.document(), generation.generation());
      forget(generation);
    });
  }

  private boolean protectedOrLocked(Generation generation) {
    var rows = database.query("SELECT index_generation::text,index_build_token::text,"
        + " COALESCE(index_lease_until>clock_timestamp(),false) FROM ai_documents"
        + " WHERE workspace_id=?::uuid AND id=?::uuid FOR UPDATE SKIP LOCKED",
        (row, n) -> generation.generation().equals(row.getString(1))
            || (generation.generation().equals(row.getString(2)) && row.getBoolean(3)),
        generation.workspace(), generation.document());
    if (!rows.isEmpty()) return rows.getFirst();
    return Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_documents"
        + " WHERE workspace_id=?::uuid AND id=?::uuid)", Boolean.class,
        generation.workspace(), generation.document()));
  }

  private void forget(Generation generation) {
    database.update("DELETE FROM index_gc_candidates WHERE workspace_id=?::uuid AND document_id=?::uuid"
        + " AND generation=?::uuid", generation.workspace(), generation.document(), generation.generation());
  }

  @Override public boolean isRunning() { return running; }
  @Override public void start() {
    running = true;
    if (tasks.consumes()) timer.scheduleWithFixedDelay(() -> {
      try { sweep(); }
      catch (Exception e) { if (running) log.warn("Search generation cleanup pending"); }
    }, 60, 60, TimeUnit.SECONDS);
  }
  @Override public void stop() { running = false; timer.shutdownNow(); }
}
