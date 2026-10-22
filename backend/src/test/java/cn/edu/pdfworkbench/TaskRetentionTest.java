package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.tasks.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TaskRetentionTest {
  static final TestServices DATABASE=new TestServices();
  final TaskSettings settings=new TaskSettings("pdf-workbench.retention","web",30,128,3,900);
  final TaskStore store=new TaskStore(DATABASE.jdbc(),settings);
  final TaskRetentionSettings retention=new TaskRetentionSettings(7,30,30);
  @AfterAll static void close(){DATABASE.close();}
  @AfterEach void reset(){DATABASE.jdbc().execute("TRUNCATE work_tasks,task_dead_letters CASCADE");}
  TaskStore.Task enqueue(TaskStore.Kind kind) {
    String doc=UUID.randomUUID().toString();
    return store.enqueue(UUID.randomUUID().toString(),kind,doc,doc,kind==TaskStore.Kind.GENERATE?UUID.randomUUID().toString():null);
  }
  void age(TaskStore.Task task) {
    DATABASE.jdbc().update("UPDATE work_tasks SET updated_at=clock_timestamp()-interval '31 days' WHERE id=?::uuid",task.id());
  }
  void publishOld(TaskStore.Task task) {
    DATABASE.jdbc().update("UPDATE task_outbox SET published_at=clock_timestamp()-interval '8 days' WHERE task_id=?::uuid",task.id());
  }
  void finish(TaskStore.Task task){store.succeed(store.claim(task.id(),task.version(),task.kind().route),()->{});age(task);}
  int count(String table){return DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

  @Test void expiredPublishedEventsAreRemovedBeforeTerminalTasks() {
    var task=enqueue(TaskStore.Kind.INDEX);finish(task);publishOld(task);
    var result=store.pruneHistory(retention);
    assertEquals(1,result.events());assertEquals(1,result.tasks());assertEquals(0,result.deadLetters());
    assertNull(store.get(task.id()));assertEquals(0,count("task_outbox"));
  }
  @Test void pendingAndRecentlyPublishedEventsProtectTheirParentTasks() {
    var pending=enqueue(TaskStore.Kind.INDEX);finish(pending);
    var recent=enqueue(TaskStore.Kind.INDEX);finish(recent);
    DATABASE.jdbc().update("UPDATE task_outbox SET published_at=clock_timestamp() WHERE task_id=?::uuid",recent.id());
    var result=store.pruneHistory(retention);
    assertEquals(0,result.events());assertEquals(0,result.tasks());assertNotNull(store.get(pending.id()));assertNotNull(store.get(recent.id()));
  }
  @Test void currentActiveDeliveryAndLivePublisherLeaseAreRetained() {
    var queued=enqueue(TaskStore.Kind.INDEX);publishOld(queued);age(queued);
    var running=enqueue(TaskStore.Kind.INDEX);store.claim(running.id(),1,"index");publishOld(running);age(running);
    var retry=enqueue(TaskStore.Kind.INDEX);store.fail(store.claim(retry.id(),1,"index"),"FIXTURE","retry",true);publishOld(retry);age(retry);
    var leased=enqueue(TaskStore.Kind.INDEX);finish(leased);publishOld(leased);
    DATABASE.jdbc().update("UPDATE task_outbox SET lease_id=?::uuid,lease_until=clock_timestamp()+interval '1 minute' WHERE task_id=?::uuid",UUID.randomUUID().toString(),leased.id());
    var result=store.pruneHistory(retention);
    assertEquals(1,result.events());assertEquals(0,result.tasks());
    assertEquals(1,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_outbox WHERE task_id=?::uuid AND dispatch_version=2",Integer.class,retry.id()));
    for(var task:List.of(queued,running,retry,leased))assertNotNull(store.get(task.id()));
  }
  @Test void cleanupTombstoneAndRunningMessageRemainRecoverable() {
    var clean=enqueue(TaskStore.Kind.CLEAN);store.fail(store.claim(clean.id(),1,"cleanup"),"FIXTURE","unavailable",false);age(clean);publishOld(clean);
    DATABASE.jdbc().update("INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,'{\"status\":\"DELETING\"}')",clean.workspace(),clean.document());
    var reading=enqueue(TaskStore.Kind.GENERATE);finish(reading);publishOld(reading);
    DATABASE.jdbc().update("INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,'{}')",reading.workspace(),reading.document());
    DATABASE.jdbc().update("INSERT INTO ai_conversations(workspace_id,id,doc_id,updated,payload) VALUES(?::uuid,?::uuid,?::uuid,1,'{}')",reading.workspace(),reading.conversation(),reading.document());
    DATABASE.jdbc().update("INSERT INTO ai_messages(workspace_id,id,conversation_id,created,payload) VALUES(?::uuid,?::uuid,?::uuid,1,'{\"status\":\"RUNNING\"}')",reading.workspace(),reading.target(),reading.conversation());
    assertEquals(0,store.pruneHistory(retention).tasks());
    assertNotNull(store.get(clean.id()));assertNotNull(store.get(reading.id()));
    DATABASE.jdbc().update("UPDATE ai_messages SET payload='{\"status\":\"COMPLETED\"}' WHERE workspace_id=?::uuid AND id=?::uuid",reading.workspace(),reading.target());
    assertEquals(1,store.pruneHistory(retention).tasks());
    assertNotNull(store.get(clean.id()));assertNull(store.get(reading.id()));
    assertEquals(1,DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM ai_messages WHERE workspace_id=?::uuid AND id=?::uuid",Integer.class,reading.workspace(),reading.target()));
  }
  @Test void historyDeletionIsBoundedAndConcurrentSweepsDoNotDoubleDelete() throws Exception {
    DATABASE.jdbc().update("INSERT INTO work_tasks(id,workspace_id,kind,target_id,document_id,state,updated_at)"
        + " SELECT gen_random_uuid(),gen_random_uuid(),'INDEX',gen_random_uuid(),gen_random_uuid(),'SUCCEEDED',clock_timestamp()-interval '31 days' FROM generate_series(1,260)");
    var second=new TaskStore(DATABASE.jdbc(),settings);
    try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a=pool.submit(()->store.pruneHistory(retention));var b=pool.submit(()->second.pruneHistory(retention));
      int first=a.get(10,java.util.concurrent.TimeUnit.SECONDS).tasks(),next=b.get(10,java.util.concurrent.TimeUnit.SECONDS).tasks();
      assertEquals(128,first);assertEquals(128,next);assertEquals(4,count("work_tasks"));
    }
    assertEquals(4,store.pruneHistory(retention).tasks());assertEquals(0,count("work_tasks"));
  }
  @Test void deadLetterExpiryIsNamespaceScopedAndUsesLastSeenTime() {
    String fingerprint="a".repeat(64);
    DATABASE.jdbc().update("INSERT INTO task_dead_letters(namespace,fingerprint,body_bytes,first_seen,last_seen) VALUES(?,?,1,clock_timestamp()-interval '31 days',clock_timestamp()-interval '31 days')",settings.namespace(),fingerprint);
    DATABASE.jdbc().update("INSERT INTO task_dead_letters(namespace,fingerprint,body_bytes,first_seen,last_seen) VALUES(?,?,1,clock_timestamp()-interval '31 days',clock_timestamp()-interval '31 days')","pdf-workbench.other",fingerprint);
    DATABASE.jdbc().update("INSERT INTO task_dead_letters(namespace,fingerprint,body_bytes,first_seen,last_seen) VALUES(?,?,1,clock_timestamp()-interval '31 days',clock_timestamp())",settings.namespace(),"b".repeat(64));
    assertEquals(1,store.pruneHistory(retention).deadLetters());assertEquals(2,count("task_dead_letters"));
  }
  @Test void retentionConfigurationRejectsZeroNegativeAndExcessivePeriods() {
    assertThrows(IllegalArgumentException.class,()->new TaskRetentionSettings(0,30,30));
    assertThrows(IllegalArgumentException.class,()->new TaskRetentionSettings(7,-1,30));
    assertThrows(IllegalArgumentException.class,()->new TaskRetentionSettings(7,30,3651));
    assertDoesNotThrow(()->new TaskRetentionSettings(1,3650,30));
  }
  @Test void eventAndArchiveDeletionAreBoundedBeforeParentRemoval() {
    var task=enqueue(TaskStore.Kind.INDEX);finish(task);publishOld(task);
    DATABASE.jdbc().update("INSERT INTO task_outbox(task_id,dispatch_version,route,published_at) SELECT ?::uuid,n,'index',clock_timestamp()-interval '8 days' FROM generate_series(2,130) n",task.id());
    DATABASE.jdbc().update("INSERT INTO task_dead_letters(namespace,fingerprint,body_bytes,first_seen,last_seen) SELECT ?,lpad(to_hex(n),64,'0'),1,clock_timestamp()-interval '31 days',clock_timestamp()-interval '31 days' FROM generate_series(1,130) n",settings.namespace());
    var first=store.pruneHistory(retention);
    assertEquals(128,first.events());assertEquals(128,first.deadLetters());assertEquals(0,first.tasks());
    var next=store.pruneHistory(retention);
    assertEquals(2,next.events());assertEquals(2,next.deadLetters());assertEquals(1,next.tasks());
  }
}
