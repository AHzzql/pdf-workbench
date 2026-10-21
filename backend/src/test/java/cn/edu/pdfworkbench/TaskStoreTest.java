package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.tasks.*;
import cn.edu.pdfworkbench.tasks.TaskStore.*;
import cn.edu.pdfworkbench.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.*;

class TaskStoreTest {
  static final TestServices DATABASE = new TestServices();
  @AfterAll static void close() { DATABASE.close(); }
  @AfterEach void reset() { DATABASE.jdbc().execute("TRUNCATE work_tasks CASCADE"); }
  final TaskStore store = new TaskStore(DATABASE.jdbc(), new TaskSettings("pdf-workbench.test", "all", 2, 128, 2, 60));
  final String owner = UUID.randomUUID().toString(), document = UUID.randomUUID().toString();
  Task index() { return store.enqueue(owner, Kind.INDEX, document, document, null); }
  void due(Task task) { DATABASE.jdbc().update("UPDATE work_tasks SET available_at=clock_timestamp()-interval '1 second' WHERE id=?::uuid", task.id()); }

  @Test
  void businessTransactionAndOutboxRollbackTogether() {
    assertThrows(IllegalStateException.class, () -> store.submission(() -> {
      index();
      throw new IllegalStateException("rollback fixture");
    }));
    assertEquals(0, DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM work_tasks", Integer.class));
    assertEquals(0, DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_outbox", Integer.class));
    var task = index();
    assertEquals(task.id(), index().id());
    assertEquals(1, DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_outbox", Integer.class));
  }

  @Test
  void duplicateStaleAndWrongRouteDeliveriesCannotClaimWork() {
    var task = index();
    assertNull(store.claim(task.id(), task.version(), "reading"));
    assertNull(store.claim(task.id(), task.version()+1, "index"));
    var claim = store.claim(task.id(), task.version(), "index");
    assertNotNull(claim); assertEquals(1, claim.attempt()); assertEquals(1, claim.fence());
    assertNull(store.claim(task.id(), task.version(), "index"));
    assertTrue(store.heartbeat(claim));
    store.succeed(claim, () -> {});
    assertFalse(store.active(claim));
    assertThrows(LeaseLost.class, () -> store.succeed(claim, () -> fail("stale result")));
    assertEquals("SUCCEEDED", store.get(task.id()).state());
  }

  @Test
  void expiredLeaseRecoversWithNewDeliveryVersionAndFencingToken() {
    var task = index(); var first = store.claim(task.id(), 1, "index");
    DATABASE.jdbc().update("UPDATE work_tasks SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?::uuid", task.id());
    assertFalse(store.heartbeat(first));
    assertEquals(1, store.recoverExpired());
    assertThrows(LeaseLost.class, () -> store.succeed(first, () -> fail("expired result")));
    var retry = store.get(task.id()); assertEquals("RETRY", retry.state()); assertEquals(2, retry.version());
    assertNull(store.claim(task.id(), 1, "index"));
    due(retry); var next = store.claim(task.id(), 2, "index");
    assertNotNull(next); assertEquals(2, next.fence());
    assertThrows(LeaseLost.class, () -> store.fenced(first, () -> fail("old owner")));
    store.succeed(next, () -> {});
  }

  @Test
  void boundedRetriesProduceOneDurableDeadLetterEvent() {
    var task = index(); var first = store.claim(task.id(), 1, "index");
    store.fail(first, "FIXTURE", "temporary", true);
    var retry = store.get(task.id()); due(retry);
    var second = store.claim(task.id(), retry.version(), "index");
    store.fail(second, "FIXTURE", "permanent", true);
    assertEquals("FAILED", store.get(task.id()).state());
    assertEquals(1, DATABASE.jdbc().queryForObject("SELECT COUNT(*) FROM task_outbox WHERE route='dead'", Integer.class));
    assertThrows(LeaseLost.class, () -> store.fail(second, "FIXTURE", "duplicate", true));
  }

  @Test
  void outboxPublishLeaseGuardsConfirmationAndBackoff() {
    index(); var first = store.nextEvent(); assertNotNull(first); assertNull(store.nextEvent());
    DATABASE.jdbc().update("UPDATE task_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.id());
    var next = store.nextEvent(); assertNotEquals(first.lease(), next.lease());
    store.published(first);
    assertNull(DATABASE.jdbc().queryForObject("SELECT published_at FROM task_outbox WHERE id=?", java.sql.Timestamp.class, first.id()));
    store.publishFailed(next); assertNull(store.nextEvent());
    DATABASE.jdbc().update("UPDATE task_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE id=?", first.id());
    var resumed = store.nextEvent(); store.published(resumed); assertNull(store.nextEvent());
  }

  @Test
  void indexPublicationAndTombstoneUseTheSameTaskBeforeDocumentLockOrder() throws Exception {
    var settings=new cn.edu.pdfworkbench.ai.AiSettings(System.getProperty("java.io.tmpdir"),"","http://127.0.0.1:9","http://127.0.0.1:9","chat","embed","rank");
    var repository=new cn.edu.pdfworkbench.ai.AiRepository(settings,new com.fasterxml.jackson.databind.ObjectMapper(),DATABASE.jdbc());
    var retrieval=org.mockito.Mockito.mock(cn.edu.pdfworkbench.ai.AiRetrieval.class);
    var documents=new cn.edu.pdfworkbench.ai.AiDocuments(repository,settings,retrieval,store);
    String token=UUID.randomUUID().toString();
    try(var scope=repository.scope(owner,()->true)) {
      repository.save(new cn.edu.pdfworkbench.ai.AiTypes.Document(document,"fixture.pdf","hash",settings.indexVersion(),1,false,"PARSED","",1));
      assertTrue(repository.claimIndex(document,token));
    }
    var claim=store.claim(index().id(),1,"index");
    var locked=new java.util.concurrent.CountDownLatch(1);
    try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var publish=pool.submit(()->{
        try(var scope=repository.scope(owner,()->true)) {
          return store.fenced(claim,()->{
            locked.countDown();
            try {Thread.sleep(150);}catch(InterruptedException e){throw new RuntimeException(e);}
            return repository.completeIndex(document,token,settings.indexVersion());
          });
        }
      });
      assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
      var delete=pool.submit(()->{try(var scope=repository.scope(owner,()->true)){documents.delete(document);}return true;});
      assertTrue(publish.get(5,java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(delete.get(5,java.util.concurrent.TimeUnit.SECONDS));
    }
    try(var scope=repository.scope(owner,()->true)){assertEquals("DELETING",repository.document(document).status());}
    assertEquals("CANCELLED",store.get(claim.id()).state());
  }

  @Test
  void unclaimedDeliveryCanBeReconciledWithoutChangingItsVersion() {
    var task=index(); var event=store.nextEvent(); store.published(event);
    DATABASE.jdbc().update("UPDATE task_outbox SET published_at=clock_timestamp()-interval '61 seconds' WHERE id=?",event.id());
    assertEquals(1,store.recoverDeliveries());
    var replay=store.nextEvent(); assertEquals(event.task(),replay.task()); assertEquals(event.version(),replay.version());
    assertEquals(task.version(),store.get(task.id()).version());
    assertNotNull(store.claim(task.id(),task.version(),"index"));
    store.published(replay);
    DATABASE.jdbc().update("UPDATE task_outbox SET published_at=clock_timestamp()-interval '61 seconds' WHERE id=?",event.id());
    assertEquals(0,store.recoverDeliveries());
  }

  @Test
  void pendingCapacityIsBoundedAndCancellationFencesAcrossStoreInstances() {
    var limited = new TaskStore(DATABASE.jdbc(), new TaskSettings("pdf-workbench.test", "all", 2, 2, 2, 60));
    var task = limited.enqueue(owner, Kind.GENERATE, UUID.randomUUID().toString(), document, UUID.randomUUID().toString());
    var claimed = limited.claim(task.id(), 1, "reading");
    limited.enqueue(owner, Kind.INDEX, document, document, null);
    limited.enqueue(owner, Kind.INDEX, UUID.randomUUID().toString(), document, null);
    assertEquals(429, assertThrows(ApiException.class, () -> limited.enqueue(owner, Kind.INDEX, UUID.randomUUID().toString(), document, null)).status());
    store.cancelMessage(UUID.randomUUID().toString(), task.target()); assertTrue(limited.active(claimed));
    store.cancelMessage(owner, task.target()); assertFalse(limited.active(claimed));
    assertEquals("CANCELLED", limited.get(task.id()).state());
    assertThrows(LeaseLost.class, () -> limited.succeed(claimed, () -> fail("cancelled result")));
  }
}
