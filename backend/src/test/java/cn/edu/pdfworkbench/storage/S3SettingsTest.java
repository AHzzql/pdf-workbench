package cn.edu.pdfworkbench.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class S3SettingsTest {
  @Test
  void composeInternalObjectStoreAllowsHttp() {
    assertDoesNotThrow(() -> settings("http://objects:8333"));
  }

  @Test
  void externalHttpObjectStoreIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> settings("http://example.com:8333"));
    assertThrows(IllegalArgumentException.class, () -> settings("http://objects.example.com:8333"));
  }

  @Test
  void remoteHttpsObjectStoreRemainsValid() {
    assertDoesNotThrow(() -> settings("https://s3.example.com"));
  }

  private static S3Settings settings(String endpoint) {
    return new S3Settings(endpoint, "us-east-1", "pdf-workbench-private",
        "test-access", "test-secret");
  }
}
