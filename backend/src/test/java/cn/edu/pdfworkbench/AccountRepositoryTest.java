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
    try (var services = new TestServices()) {
    var repository = new AccountRepository(services.jdbc());
    var first = repository.insert("one@example.com", "One", "encoded-value");
    var reopened = new AccountRepository(services.jdbc());
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
  }

  @Test
  void storageErrorsAreStable() throws Exception {
    var data = new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:postgresql://127.0.0.1:9/missing?connectTimeout=1", "none", "none");
    var repository = new AccountRepository(new org.springframework.jdbc.core.JdbcTemplate(data));
    var error = assertThrows(ApiException.class, () -> repository.findId(java.util.UUID.randomUUID().toString()));
    assertEquals(503, error.status());
    assertEquals("ACCOUNT_STORAGE_UNAVAILABLE", error.code());
  }
}
