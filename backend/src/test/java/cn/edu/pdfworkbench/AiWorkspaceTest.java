package cn.edu.pdfworkbench;

import static cn.edu.pdfworkbench.ai.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiWorkspaceTest {
  @TempDir Path root;

  AiSettings settings() {
    return new AiSettings(
        root.toString(),
        "",
        "https://example.com",
        "https://example.com",
        "chat",
        "embedding",
        "rerank");
  }

  void save(AiRepository repository, String id, String text) throws Exception {
    repository.save(new Document(id, "sample.pdf", "hash", "v1", 1, false, "READY", "", 1));
    repository.save(new Conversation(id, id, text, 1, 1));
    repository.save(
        new Message(
            id, id, "assistant", text, "COMPLETED", "", "ASK", text, null, null, List.of(), 1, 1));
    Files.writeString(repository.documentPath(id), text);
  }

  @Test
  void existingGuestAndAccountHistoriesSurviveReopeningWithoutSharingIds() throws Exception {
    var repository = new AiRepository(settings(), new ObjectMapper());
    String id = UUID.randomUUID().toString(),
        a = UUID.randomUUID().toString(),
        b = UUID.randomUUID().toString();
    save(repository, id, "guest");
    try (var scope = repository.scope(a, () -> true)) {
      save(repository, id, "account-a");
      assertTrue(repository.documentPath(id).startsWith(root.resolve("users").resolve(a)));
    }
    try (var scope = repository.scope(b, () -> true)) {
      save(repository, id, "account-b");
    }
    var reopened = new AiRepository(settings(), new ObjectMapper());
    assertEquals("guest", reopened.messages(id).getFirst().text());
    assertEquals("guest", Files.readString(reopened.documentPath(id)));
    try (var scope = reopened.scope(a, () -> true)) {
      assertEquals("account-a", reopened.messages(id).getFirst().text());
      assertEquals("account-a", Files.readString(reopened.documentPath(id)));
    }
    try (var scope = reopened.scope(b, () -> true)) {
      assertEquals("account-b", reopened.messages(id).getFirst().text());
    }
    assertEquals("guest", reopened.owner());
  }

  @Test
  void scopeClosesOnFailureAndRejectsPathTraversal() {
    var repository = new AiRepository(settings(), new ObjectMapper());
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) {
            throw new IllegalStateException();
          }
        });
    assertEquals("guest", repository.owner());
    assertThrows(ApiException.class, () -> repository.scope("../../outside", () -> true));
  }
}
