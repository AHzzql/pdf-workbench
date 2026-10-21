package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ElasticsearchRetrievalTest {
  static final TestServices DATABASE = new TestServices();
  @AfterAll static void close() { DATABASE.close(); }
  @TempDir Path directory;
  final String owner = UUID.randomUUID().toString();
  final String id = UUID.randomUUID().toString();
  AiSettings settings;
  AiRepository repository;
  AiModelClient models;
  ElasticsearchIndex index;
  AiRetrieval retrieval;
  cn.edu.pdfworkbench.tasks.TaskStore tasks;

  @BeforeEach
  void setup() {
    settings = new AiSettings(directory.toString(), "", "http://127.0.0.1:9", "http://127.0.0.1:9", "chat", "embed", "rank");
    repository = new AiRepository(settings, new ObjectMapper(), DATABASE.jdbc());
    tasks = new cn.edu.pdfworkbench.tasks.TaskStore(DATABASE.jdbc(), new cn.edu.pdfworkbench.tasks.TaskSettings("pdf-workbench.unit", "all", 30, 128, 3, 900));
    models = mock(AiModelClient.class);
    index = new ElasticsearchIndex(DATABASE.searchSettings(), new ObjectMapper());
    retrieval = new AiRetrieval(repository, models, settings, index);
    when(models.embed(anyList())).thenAnswer(call -> ((List<String>) call.getArgument(0)).stream().map(text -> vector(0)).toList());
    when(models.rerank(anyString(), anyList())).thenAnswer(call -> IntStream.range(0, ((List<?>)call.getArgument(1)).size()).boxed().toList());
  }

  void document(String document) {
    repository.save(new Document(document, "fixture.pdf", "hash", settings.indexVersion(), 2, false, "PARSED", "", 1));
  }

  static Chunk chunk(String id, String text) { return new Chunk(id, 1, 0, text.length(), text, List.of()); }
  static float[] vector(int axis) { var vector = new float[1024]; vector[axis] = 1; return vector; }

  @Test
  void cjkEnglishAndVectorQueriesEnforceWorkspaceDocumentGenerationAndChunkScope() throws Exception {
    var target = List.of(chunk("p1-0", "中文文档检索 multilingual needle"), chunk("p2-0", "范围外 excluded chapter"));
    String otherDocument = UUID.randomUUID().toString(), otherOwner = UUID.randomUUID().toString();
    try (var scope = repository.scope(owner, () -> true)) {
      document(id); retrieval.index(id, target, ignored -> {});
      document(otherDocument); retrieval.index(otherDocument, List.of(chunk("p1-0", "otherdocumentsecret")), ignored -> {});
    }
    try (var scope = repository.scope(otherOwner, () -> true)) {
      document(id); retrieval.index(id, List.of(chunk("p1-0", "otherworkspacesecret")), ignored -> {});
    }
    try (var scope = repository.scope(owner, () -> true)) {
      String generation = repository.indexGeneration(id);
      for (String question : List.of("文档", "needle")) {
        var ranks = index.ranks(owner, id, generation, settings.indexVersion(), question, vector(0), List.of(target.getFirst()));
        assertEquals(List.of("p1-0"), ranks.getFirst());
        assertEquals(List.of("p1-0"), ranks.get(1));
      }
      for (String question : List.of("otherdocumentsecret", "otherworkspacesecret", "excluded"))
        assertTrue(index.ranks(owner, id, generation, settings.indexVersion(), question, vector(0), List.of(target.getFirst())).getFirst().isEmpty());
      assertEquals(List.of(target.getFirst()), retrieval.search(id, "文档", List.of(target.getFirst())));
      assertFalse(index.ready(otherOwner, id, generation, settings.indexVersion(), 2));
      assertTrue(retrieval.ready(id, 2));
    }
  }

  @Test
  void independentClientReopensSharedIndexAndRebuildPublishesOnlyNewGeneration() throws Exception {
    try (var scope = repository.scope(owner, () -> true)) {
      document(id); retrieval.index(id, List.of(chunk("p1-0", "obsoleteword")), ignored -> {});
      String oldGeneration = repository.indexGeneration(id);
      var reopened = new AiRetrieval(repository, models, settings, new ElasticsearchIndex(DATABASE.searchSettings(), new ObjectMapper()));
      assertTrue(reopened.ready(id, 1));
      reopened.index(id, List.of(chunk("p1-0", "replacementword")), ignored -> {});
      String current = repository.indexGeneration(id);
      assertNotEquals(oldGeneration, current);
      assertEquals("READY", repository.document(id).status());
      assertTrue(index.ranks(owner, id, current, settings.indexVersion(), "obsoleteword", vector(0), List.of(chunk("p1-0", "replacementword"))).getFirst().isEmpty());
      assertEquals(List.of("p1-0"), index.ranks(owner, id, current, settings.indexVersion(), "replacementword", vector(0), List.of(chunk("p1-0", "replacementword"))).getFirst());
      index.delete(owner, id, oldGeneration);
      assertTrue(reopened.ready(id, 1));
    }
  }

  @Test
  void partialBuildDoesNotPublishAndCanBeRetried() throws Exception {
    try (var scope = repository.scope(owner, () -> true)) {
      document(id);
      var chunks = IntStream.range(0, 9).mapToObj(i -> chunk("p1-" + i, "content " + i)).toList();
      var batch = new AtomicInteger();
      when(models.embed(anyList())).thenAnswer(call -> ((List<String>) call.getArgument(0)).stream()
          .map(text -> batch.incrementAndGet() > 8 ? new float[1023] : vector(0)).toList());
      assertEquals("AI_EMBEDDING_INVALID", assertThrows(ApiException.class, () -> retrieval.index(id, chunks, ignored -> {})).code());
      assertNull(repository.indexGeneration(id));
      assertEquals("PARSED", repository.document(id).status());
      assertFalse(retrieval.ready(id, chunks.size()));
      when(models.embed(anyList())).thenAnswer(call -> ((List<String>) call.getArgument(0)).stream().map(text -> vector(0)).toList());
      retrieval.index(id, chunks, ignored -> {});
      assertTrue(retrieval.ready(id, chunks.size()));
    }
  }

  @Test
  void expiredAndRevokedBuildTokensCannotPublishReadyOrClearAnotherLease() {
    try (var scope = repository.scope(owner, () -> true)) {
      document(id);
      String first = UUID.randomUUID().toString(), next = UUID.randomUUID().toString();
      assertTrue(repository.claimIndex(id, first));
      assertFalse(repository.claimIndex(id, next));
      DATABASE.jdbc().update("UPDATE ai_documents SET index_lease_until=clock_timestamp()-interval '1 second' WHERE workspace_id=?::uuid AND id=?::uuid", owner, id);
      assertFalse(repository.renewIndex(id, first));
      assertFalse(repository.completeIndex(id, first, settings.indexVersion()));
      assertTrue(repository.claimIndex(id, next));
      repository.releaseIndex(id, first);
      assertTrue(repository.renewIndex(id, next));
      assertFalse(repository.completeIndex(id, first, settings.indexVersion()));
      repository.markDeleting(id);
      assertFalse(repository.renewIndex(id, next));
      assertFalse(repository.completeIndex(id, next, settings.indexVersion()));
      assertFalse(repository.claimIndex(id, first));
      assertEquals("DOCUMENT_DELETING", assertThrows(ApiException.class, () -> retrieval.search(id, "test", List.of())).code());
    }
  }

  @Test
  void deletionDuringBuildPreventsLateReadyAndSameIdsElsewhereRemainAccessible() throws Exception {
    var chunks = IntStream.range(0, 9).mapToObj(i -> chunk("p1-" + i, "document " + i)).toList();
    try (var scope = repository.scope(owner, () -> true)) {
      document(id);
      assertEquals("INDEX_LEASE_LOST", assertThrows(ApiException.class,
          () -> retrieval.index(id, chunks, ignored -> repository.markDeleting(id))).code());
      assertNull(repository.indexGeneration(id));
      assertEquals("DELETING", repository.document(id).status());
    }
    String secondOwner = UUID.randomUUID().toString();
    try (var scope = repository.scope(secondOwner, () -> true)) {
      document(id); retrieval.index(id, List.of(chunk("p1-0", "retained")), ignored -> {});
    }
    try (var scope = repository.scope(owner, () -> true)) {
      new AiDocuments(repository, settings, retrieval, tasks).delete(id);
      new AiDocuments(repository, settings, retrieval, tasks).cleanFiles(id);
      repository.deleteDocumentRecord(id);
      assertEquals(404, assertThrows(ApiException.class, () -> repository.document(id)).status());
    }
    try (var scope = repository.scope(secondOwner, () -> true)) { assertTrue(retrieval.ready(id, 1)); }
  }

  @Test
  void cancellationReleasesTheDatabaseLeaseAndPreservesInterruptStatus() throws Exception {
    when(models.embed(anyList())).thenAnswer(call -> {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    });
    verifyCancellationReleasesLease(retrieval);
  }

  @Test
  void interruptionAfterAClaimWithUnknownOutcomeStillReleasesItsToken() throws Exception {
    var uncertain = spy(repository);
    doAnswer(call -> {
      call.callRealMethod();
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    }).when(uncertain).claimIndex(eq(id), anyString());
    verifyCancellationReleasesLease(new AiRetrieval(uncertain, models, settings, index));
  }

  private void verifyCancellationReleasesLease(AiRetrieval cancelled) throws Exception {
    try (var scope = repository.scope(owner, () -> true)) { document(id); }
    var restored = new java.util.concurrent.atomic.AtomicBoolean();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var worker = Thread.startVirtualThread(() -> {
      try (var scope = repository.scope(owner, () -> true)) {
        try { cancelled.index(id, List.of(chunk("p1-0", "cancelled")), ignored -> {}); }
        catch (java.util.concurrent.CancellationException e) { restored.set(Thread.currentThread().isInterrupted()); }
        catch (Throwable e) { failure.set(e); }
        finally { Thread.interrupted(); }
      }
    });
    worker.join(10000);
    assertFalse(worker.isAlive());
    assertNull(failure.get());
    assertTrue(restored.get());
    try (var scope = repository.scope(owner, () -> true)) {
      assertNull(repository.indexGeneration(id));
      assertTrue(repository.claimIndex(id, UUID.randomUUID().toString()));
    }
  }

  @Test
  void deletionFailureKeepsTombstoneAndRetryCompletesCleanup() throws Exception {
    try (var scope = repository.scope(owner, () -> true)) {
      document(id);
      repository.content(id, new Content(List.of(chunk("p1-0", "source")), List.of()));
      java.nio.file.Files.write(repository.documentPath(id), new byte[] {1,2,3});
      var unavailable = mock(AiRetrieval.class);
      doThrow(new ApiException(503, "SEARCH_UNAVAILABLE", "fixture")).when(unavailable).delete(id);
      var documents = new AiDocuments(repository, settings, unavailable, tasks);
      assertEquals(503, assertThrows(ApiException.class, () -> { documents.delete(id); documents.cleanFiles(id); }).status());
      assertEquals("DELETING", repository.document(id).status());
      assertTrue(java.nio.file.Files.exists(repository.documentPath(id)));
      assertEquals("DOCUMENT_DELETING", assertThrows(ApiException.class, () -> documents.createConversation(id)).code());
      new AiDocuments(repository, settings, retrieval, tasks).delete(id);
      new AiDocuments(repository, settings, retrieval, tasks).cleanFiles(id);
      repository.deleteDocumentRecord(id);
      assertFalse(java.nio.file.Files.exists(repository.documentPath(id)));
      assertEquals(404, assertThrows(ApiException.class, () -> repository.document(id)).status());
    }
  }

  @Test
  void databaseLockSerializesConversationCreationWithDeletion() throws Exception {
    for (int iteration = 0; iteration < 8; iteration++) {
      String document = UUID.randomUUID().toString();
      try (var scope = repository.scope(owner, () -> true)) { document(document); }
      var start = new java.util.concurrent.CountDownLatch(1);
      try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var created = pool.submit(() -> {
          start.await();
          try (var scope = repository.scope(owner, () -> true)) {
            repository.createConversation(new Conversation(UUID.randomUUID().toString(), document, "title", 1, 1));
            return true;
          } catch (ApiException e) { assertEquals("DOCUMENT_DELETING", e.code()); return false; }
        });
        var deleted = pool.submit(() -> {
          start.await();
          try (var scope = repository.scope(owner, () -> true)) { repository.markDeleting(document); return true; }
          catch (ApiException e) { assertEquals("DOCUMENT_IN_USE", e.code()); return false; }
        });
        start.countDown();
        assertNotEquals(created.get(5, java.util.concurrent.TimeUnit.SECONDS), deleted.get(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void authenticationAndConnectionFailuresAreNotEmptyResults() {
    var actual = DATABASE.searchSettings();
    var unauthorized = new ElasticsearchIndex(new ElasticsearchSettings(actual.url(), "elastic", "invalid-fixture", actual.alias()), new ObjectMapper());
    assertEquals("SEARCH_UNAVAILABLE", assertThrows(ApiException.class, unauthorized::ensureIndex).code());
    var unavailable = new ElasticsearchIndex(new ElasticsearchSettings("http://127.0.0.1:9", "elastic", "fixture", actual.alias()), new ObjectMapper());
    assertEquals("SEARCH_UNAVAILABLE", assertThrows(ApiException.class, () -> unavailable.ready(owner, id, UUID.randomUUID().toString(), settings.indexVersion(), 1)).code());
    assertFalse(actual.toString().contains(actual.password()));
  }
}
