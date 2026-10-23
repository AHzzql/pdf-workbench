package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.ElasticsearchIndex.*;
import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.tasks.TaskSettings;
import java.util.*;
import org.junit.jupiter.api.*;

class IndexMaintenanceTest {
  static final TestServices DATABASE = new TestServices();
  @AfterAll static void close() { DATABASE.close(); }
  final String owner = UUID.randomUUID().toString();
  final String document = UUID.randomUUID().toString();
  final String generation = UUID.randomUUID().toString();
  final Generation item = new Generation(owner, document, generation);
  ElasticsearchIndex index;
  IndexMaintenance maintenance;

  @BeforeEach void setup() {
    index = mock(ElasticsearchIndex.class);
    when(index.generations(anyMap(), eq(32))).thenReturn(new GenerationPage(List.of(item), Map.of()));
    maintenance = new IndexMaintenance(DATABASE.jdbc(), index,
        new TaskSettings("pdf-workbench.gc-test", "all", 30, 128, 3, 900), 600);
    DATABASE.jdbc().update("INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,'{}'::jsonb)", owner, document);
  }
  @AfterEach void stop() {
    maintenance.stop();
    DATABASE.jdbc().update("DELETE FROM index_gc_candidates WHERE workspace_id=?::uuid", owner);
    DATABASE.jdbc().update("DELETE FROM ai_documents WHERE workspace_id=?::uuid", owner);
  }
  int candidates() { return DATABASE.jdbc().queryForObject("SELECT count(*) FROM index_gc_candidates WHERE workspace_id=?::uuid", Integer.class, owner); }
  void age() { DATABASE.jdbc().update("UPDATE index_gc_candidates SET observed_at=clock_timestamp()-interval '11 minutes' WHERE workspace_id=?::uuid", owner); }

  @Test void observesBeforeDeletingAndPreservesAuthoritativeDocument() {
    maintenance.sweep(); assertEquals(1, candidates()); verify(index, never()).delete(anyString(), anyString(), anyString());
    maintenance.sweep(); assertEquals(1, candidates());
    age(); maintenance.sweep();
    verify(index).delete(owner, document, generation); assertEquals(0, candidates());
    assertEquals(1, DATABASE.jdbc().queryForObject("SELECT count(*) FROM ai_documents WHERE workspace_id=?::uuid", Integer.class, owner));
  }
  @Test void currentAndLiveBuildGenerationsAreProtected() {
    DATABASE.jdbc().update("UPDATE ai_documents SET index_generation=?::uuid WHERE workspace_id=?::uuid", generation, owner);
    maintenance.sweep(); assertEquals(0, candidates());
    DATABASE.jdbc().update("UPDATE ai_documents SET index_generation=NULL,index_build_token=?::uuid,index_lease_until=clock_timestamp()+interval '10 minutes' WHERE workspace_id=?::uuid", generation, owner);
    maintenance.sweep(); assertEquals(0, candidates());
    verify(index, never()).delete(anyString(), anyString(), anyString());
  }
  @Test void publicationDuringGraceCancelsCandidate() {
    maintenance.sweep(); age();
    DATABASE.jdbc().update("UPDATE ai_documents SET index_generation=?::uuid WHERE workspace_id=?::uuid", generation, owner);
    maintenance.sweep(); assertEquals(0, candidates());
    verify(index, never()).delete(anyString(), anyString(), anyString());
  }
  @Test void failedDeletionRetainsCandidateForRetry() {
    maintenance.sweep(); age();
    doThrow(new ApiException(503,"SEARCH_UNAVAILABLE","fixture")).doNothing().when(index).delete(owner, document, generation);
    assertThrows(ApiException.class, maintenance::sweep); assertEquals(1, candidates());
    maintenance.sweep(); assertEquals(0, candidates()); verify(index, times(2)).delete(owner, document, generation);
  }
  @Test void expiredBuildAndMissingDocumentAreReconciled() {
    DATABASE.jdbc().update("UPDATE ai_documents SET index_build_token=?::uuid,index_lease_until=clock_timestamp()-interval '1 minute' WHERE workspace_id=?::uuid", generation, owner);
    maintenance.sweep(); age();
    DATABASE.jdbc().update("DELETE FROM ai_documents WHERE workspace_id=?::uuid", owner);
    maintenance.sweep(); assertEquals(0, candidates()); verify(index).delete(owner, document, generation);
  }
  @Test void candidateSurvivesMaintenanceRestartAndMissingSearchBucket() {
    maintenance.sweep(); age(); maintenance.stop();
    when(index.generations(anyMap(), eq(32))).thenReturn(new GenerationPage(List.of(), Map.of()));
    maintenance = new IndexMaintenance(DATABASE.jdbc(), index,
        new TaskSettings("pdf-workbench.gc-test", "all", 30, 128, 3, 900), 600);
    maintenance.sweep(); assertEquals(0, candidates()); verify(index).delete(owner, document, generation);
  }
  @Test void lockedDocumentIsSkippedWithoutDeletingItsSearchGeneration() throws Exception {
    maintenance.sweep(); age();
    try (var connection = DATABASE.jdbc().getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("SELECT 1 FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid FOR UPDATE")) {
        statement.setString(1, owner); statement.setString(2, document);
        try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
        maintenance.sweep();
        verify(index, never()).delete(anyString(), anyString(), anyString());
      } finally { connection.rollback(); }
    }
    maintenance.sweep(); age(); maintenance.sweep();
    verify(index).delete(owner, document, generation);
  }

  @Test void realElasticsearchPaginationAndCleanupPreserveCurrentAndLiveBuild() {
    var actual = new ElasticsearchIndex(DATABASE.searchSettings(), new com.fasterxml.jackson.databind.ObjectMapper());
    actual.ensureIndex();
    String current = UUID.randomUUID().toString(), building = UUID.randomUUID().toString();
    var vector = new float[1024]; vector[0] = 1;
    var chunks = List.of(new AiTypes.Chunk("p1-0", 1, 0, 7, "fixture", List.of()));
    for (String token : List.of(current, building, generation))
      actual.write(owner, document, token, "fixture-version", chunks, List.of(vector));
    actual.refresh();
    DATABASE.jdbc().update("UPDATE ai_documents SET index_generation=?::uuid,index_build_token=?::uuid,"
        + "index_lease_until=clock_timestamp()+interval '10 minutes' WHERE workspace_id=?::uuid", current, building, owner);
    var found = new HashSet<String>();
    Map<String,String> cursor = Map.of();
    for (int pages = 0; pages < 20; pages++) {
      var page = actual.generations(cursor, 1);
      if (page.items().isEmpty()) break;
      assertEquals(1, page.items().size());
      assertTrue(found.add(page.items().getFirst().generation()));
      cursor = page.after();
    }
    assertEquals(Set.of(current, building, generation), found);
    maintenance.stop();
    maintenance = new IndexMaintenance(DATABASE.jdbc(), actual,
        new TaskSettings("pdf-workbench.gc-test", "all", 30, 128, 3, 900), 600);
    maintenance.sweep(); assertEquals(1, candidates()); age(); maintenance.sweep();
    assertFalse(actual.ready(owner, document, generation, "fixture-version", 1));
    assertTrue(actual.ready(owner, document, current, "fixture-version", 1));
    assertTrue(actual.ready(owner, document, building, "fixture-version", 1));
    assertEquals(0, candidates());
  }

}
