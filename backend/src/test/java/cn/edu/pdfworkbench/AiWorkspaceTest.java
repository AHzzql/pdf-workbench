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
    return new AiSettings(root.toString(), "", "https://example.invalid", "https://example.invalid", "chat", "embedding", "rerank");
  }

  void save(AiRepository repository, String id, String text) throws Exception {
    repository.save(new Document(id, "sample.pdf", "hash", "v1", 1, false, "READY", "", 1));
    repository.save(new Conversation(id, id, text, 1, 1));
    repository.save(new Message(id, id, "assistant", text, "COMPLETED", "", "ASK", text, null, null, List.of(), 1, 1));
    Files.createDirectories(repository.root());
    Files.writeString(repository.documentPath(id), text);
  }

  @Test
  void sharedDatabasePersistsIndependentSpacesAndCascadesOnlyTheSelectedConversation() throws Exception {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc());
      String id = UUID.randomUUID().toString(), a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
      try (var scope = repository.scope(a, () -> true)) { save(repository, id, "space-a"); }
      try (var scope = repository.scope(b, () -> true)) { save(repository, id, "space-b"); }
      var reopened = new AiRepository(settings(), new ObjectMapper(), database.jdbc());
      assertThrows(ApiException.class, reopened::owner);
      try (var scope = reopened.scope(a, () -> true)) {
        assertEquals("space-a", reopened.messages(id).getFirst().text());
        assertEquals("space-a", Files.readString(reopened.documentPath(id)));
        assertTrue(reopened.documentPath(id).startsWith(root.resolve("users").resolve(a)));
        reopened.deleteConversation(id);
        assertThrows(ApiException.class, () -> reopened.message(id));
      }
      try (var scope = reopened.scope(b, () -> true)) {
        assertEquals("space-b", reopened.messages(id).getFirst().text());
        assertEquals("space-b", Files.readString(reopened.documentPath(id)));
      }
    }
  }

  @Test
  void scopeFailsClosedRestoresOnFailureAndRejectsNonCanonicalIds() {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc());
      assertThrows(ApiException.class, repository::documents);
      assertThrows(IllegalStateException.class, () -> {
        try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) { throw new IllegalStateException(); }
      });
      assertThrows(ApiException.class, repository::owner);
      for (String invalid : List.of("../../outside", "guest", "------------------------------------"))
        assertThrows(ApiException.class, () -> repository.scope(invalid, () -> true));
    }
  }

  @Test
  void compositeForeignKeysRejectLinksAcrossSpaces() throws Exception {
    try (var database = new TestServices()) {
      var repository = new AiRepository(settings(), new ObjectMapper(), database.jdbc());
      String id = UUID.randomUUID().toString();
      try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) { save(repository, id, "private"); }
      try (var scope = repository.scope(UUID.randomUUID().toString(), () -> true)) {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> repository.save(new Conversation(UUID.randomUUID().toString(), id, "invalid", 1, 1)));
      }
    }
  }
}
