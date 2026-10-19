package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.auth.*;
import cn.edu.pdfworkbench.exception.ApiException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AccountRepositoryTest {
  @TempDir Path directory;

  @Test
  void persistsAndRejectsDuplicateNormalizedEmail() {
    var repository = new AccountRepository(directory.toString());
    var first = repository.insert("one@example.com", "One", "encoded-value");
    var reopened = new AccountRepository(directory.toString());
    assertEquals(first, reopened.findId(first.id()).orElseThrow());
    assertEquals(
        409,
        assertThrows(
                ApiException.class,
                () -> reopened.insert("one@example.com", "Two", "encoded-value"))
            .status());
    assertTrue(repository.password(first, "updated-encoded-value"));
    assertFalse(repository.password(first, "stale-encoded-value"));
    assertEquals(2, reopened.findId(first.id()).orElseThrow().version());
  }

  @Test
  void initializationIsLazyAndStorageErrorsAreStable() throws Exception {
    Path file = Files.writeString(directory.resolve("not-a-directory"), "fixture");
    var repository = new AccountRepository(file.toString());
    var error = assertThrows(ApiException.class, () -> repository.findId("anything"));
    assertEquals(503, error.status());
    assertEquals("ACCOUNT_STORAGE_UNAVAILABLE", error.code());
  }
}
