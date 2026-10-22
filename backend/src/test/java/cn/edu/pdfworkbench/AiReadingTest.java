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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiReadingTest {
  @TempDir Path root;

  @Test
  void scopeRestrictsBothPagesAndChapterOffsets() {
    var chunks =
        List.of(
            chunk("p1-0", 1, 0, "First"),
            chunk("p1-10", 1, 10, "Second"),
            chunk("p2-0", 2, 0, "Third"));
    var content =
        new Content(chunks, List.of(new Chapter("chapter", "Second", 1, 10, 2, 1, "HEADING")));
    assertEquals(List.of(chunks.get(1)), AiReading.select(content, "1", "chapter", 2));
    assertThrows(IllegalArgumentException.class, () -> AiReading.select(content, "3", null, 2));
    assertThrows(ApiException.class, () -> AiReading.select(content, null, "unknown", 2));
  }

  @Test
  void citationsCannotEscapeRetrievedEvidence() {
    var citation = new Citation("p2-0", "document", 2, "Evidence", List.of());
    String text = "First [p2-0], again [p2-0], invented [p999-0]";
    assertEquals(List.of(citation), AiReading.usedCitations(text, List.of(citation)));
    assertFalse(AiReading.validateReferences(text, List.of(citation)).contains("p999-0"));
  }

  @Test
  void longSummaryUsesMultipleEvidenceGroupsAndPreservesSourceIds() throws Exception {
    var repository = mock(AiRepository.class);
    var documents = mock(AiDocuments.class);
    var model = mock(AiModelClient.class);
    var retrieval = mock(AiRetrieval.class);
    var chunks = new ArrayList<Chunk>();
    for (int i = 0; i < 30; i++)
      chunks.add(chunk("p" + (i + 1) + "-0", i + 1, 0, "Evidence ".repeat(120)));
    var document = new Document("d", "long.pdf", "hash", "v", 30, false, "READY", "", 0);
    when(documents.prepare(eq("d"), any())).thenReturn(new Content(chunks, List.of()));
    when(repository.document("d")).thenReturn(document);
    when(model.complete(anyString(), anyString())).thenReturn("Summary [p1-0]");
    doAnswer(
        call -> {
          ((Consumer<String>) call.getArgument(2)).accept("Final [p1-0]");
          return null;
        })
        .when(model)
        .stream(anyString(), anyString(), any());
    var stages = new ArrayList<String>();
    var answer = new StringBuilder();
    var result =
        new AiReading(
                repository, model, documents, retrieval, new AiIntent(model, new ObjectMapper()))
            .generate(
                new Conversation("c", "d", "title", 0, 0),
                new Message(
                    "m",
                    "c",
                    "assistant",
                    "",
                    "RUNNING",
                    "",
                    "DETAILED",
                    "重点方法",
                    null,
                    null,
                    List.of(),
                    1,
                    0),
                stages::add,
                answer::append);
    verify(model, atLeast(2)).complete(anyString(), anyString());
    verify(model).stream(anyString(), contains("重点方法"), any());
    assertEquals("Final [p1-0]", answer.toString());
    assertEquals(30, result.size());
    assertTrue(stages.stream().anyMatch(s -> s.startsWith("分层摘要")));
    verifyNoInteractions(retrieval);
  }

  @Test
  void repositoryReopensHistoryWithoutStoppingAnotherInstancesRun() {
    try (var database = new TestServices()) {
    var settings =
        new AiSettings(
            root.toString(),
            "",
            "https://example.invalid",
            "https://example.invalid",
            "chat",
            "embed",
            "rank");
    var json = new ObjectMapper();
    var first = new AiRepository(settings, json, database.jdbc(),database.files());
    String owner = UUID.randomUUID().toString();
    try (var scope = first.scope(owner, () -> true)) {
    String d = UUID.randomUUID().toString(),
        c = UUID.randomUUID().toString(),
        m = UUID.randomUUID().toString();
    first.save(new Document(d, "history.pdf", "hash", "v", 1, false, "READY", "", 1));
    first.save(new Conversation(c, d, "History", 1, 1));
    first.save(
        new Message(
            m,
            c,
            "assistant",
            "partial",
            "RUNNING",
            "",
            "ASK",
            "question",
            null,
            null,
            List.of(),
            1,
            3));
    var reopened = new AiRepository(settings, json, database.jdbc(),database.files());
    try (var otherScope = reopened.scope(owner, () -> true)) {
    assertEquals("RUNNING", reopened.message(m).status());
    assertEquals("partial", reopened.messages(c).getFirst().text());
    assertEquals(3, reopened.message(m).sequence());
    assertEquals("History", reopened.conversations().getFirst().title());
    assertThrows(ApiException.class, () -> reopened.pdf("../../secrets",root.resolve("invalid.pdf")));
    }
    }
    }
  }

  private Chunk chunk(String id, int page, int start, String text) {
    return new Chunk(id, page, start, start + text.length(), text, List.of());
  }
}
