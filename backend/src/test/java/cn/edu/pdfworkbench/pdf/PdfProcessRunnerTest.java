package cn.edu.pdfworkbench.pdf;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfProcessRunnerTest {
  @TempDir Path directory;
  private List<String> command(String mode) {
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-Xmx32m", "-XX:-UsePerfData", "-XX:+ExitOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError", "-cp",
        Path.of("target/test-classes").toAbsolutePath().toString(), PdfProcessFixture.class.getName(), mode);
  }
  private byte[] request() { return "password-private-fixture".getBytes(StandardCharsets.UTF_8); }
  private void dead() throws Exception {
    long id = Long.parseLong(Files.readString(directory.resolve("child.pid")));
    assertFalse(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false));
  }
  private void started() throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Files.exists(directory.resolve("child.pid")) && System.nanoTime() < end) Thread.sleep(20);
    assertTrue(Files.exists(directory.resolve("child.pid")));
  }

  @Test void sendsOnlyThroughStdinAndClearsRequest() throws Exception {
    byte[] request = request();
    try (var runner = new PdfProcessRunner()) {
      runner.run(command("echo"), directory, request, Duration.ofSeconds(10), 10000);
      assertEquals("24:true", Files.readString(directory.resolve("result.txt")));
      assertArrayEquals(new byte[request.length], request);
      dead();
    }
  }

  @Test void environmentExcludesCredentialsAndJvmInjection() {
    var environment = new HashMap<>(Map.of("SystemRoot", "system", "DATABASE_PASSWORD", "secret",
        "REDIS_PASSWORD", "secret", "JAVA_TOOL_OPTIONS", "-agentlib:injected", "PATH", "external"));
    PdfProcessRunner.configureEnvironment(environment, directory);
    assertEquals(Set.of("SystemRoot", "TEMP", "TMP"), environment.keySet());
    assertEquals(directory.toAbsolutePath().toString(), environment.get("TEMP"));
  }

  @Test void timeoutKillsEvenWhenStdinIsNotRead() throws Exception {
    byte[] request = new byte[65536]; Arrays.fill(request, (byte) 7);
    try (var runner = new PdfProcessRunner()) {
      long start = System.nanoTime();
      var error = assertThrows(ApiException.class, () -> runner.run(command("hang"), directory, request,
          Duration.ofSeconds(2), 10000));
      assertEquals("PDF_TIMEOUT", error.code()); assertEquals(504, error.status());
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(9));
      assertArrayEquals(new byte[request.length], request); dead();
      runner.run(command("echo"), directory, request(), Duration.ofSeconds(10), 10000);
    }
  }

  @Test void crashDoesNotExposeChildStderr() throws Exception {
    try (var runner = new PdfProcessRunner()) {
      var error = assertThrows(ApiException.class, () -> runner.run(command("crash"), directory, request(), Duration.ofSeconds(10), 10000));
      assertEquals("PDF_PROCESS_FAILED", error.code());
      assertFalse(error.getMessage().contains("fixture-private-detail")); dead();
    }
  }

  @Test void outputGrowthIsTerminated() throws Exception {
    try (var runner = new PdfProcessRunner()) {
      var error = assertThrows(ApiException.class, () -> runner.run(command("grow"), directory, request(), Duration.ofSeconds(10), 4096));
      assertEquals("PDF_WORKSPACE_LIMIT", error.code()); assertEquals(413, error.status()); dead();
      assertTrue(Files.size(directory.resolve("output.bin")) > 4096);
    }
  }

  @Test void childHeapFailureDoesNotPreventAnotherRequest() throws Exception {
    try (var runner = new PdfProcessRunner()) {
      var error = assertThrows(ApiException.class, () -> runner.run(command("memory"), directory, request(), Duration.ofSeconds(10), 10000));
      assertEquals("PDF_PROCESS_FAILED", error.code()); dead();
      try (var files = Files.list(directory)) { assertFalse(files.anyMatch(path -> path.toString().endsWith(".hprof"))); }
      runner.run(command("echo"), directory, request(), Duration.ofSeconds(10), 10000);
    }
  }

  @Test void interruptKillsChildAndPreservesInterruptFlag() throws Exception {
    try (var runner = new PdfProcessRunner()) {
      var error = new AtomicReference<ApiException>(); var interrupted = new AtomicBoolean();
      Thread owner = Thread.ofPlatform().start(() -> {
        try { runner.run(command("hang"), directory, request(), Duration.ofSeconds(30), 10000); }
        catch (ApiException e) { error.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      started(); owner.interrupt(); owner.join(10000);
      assertFalse(owner.isAlive()); assertNotNull(error.get()); assertEquals("PDF_INTERRUPTED", error.get().code());
      assertTrue(interrupted.get()); dead();
    }
  }

  @Test void closingRunnerStopsActiveChildAndRejectsMoreWork() throws Exception {
    var runner = new PdfProcessRunner();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending = executor.submit(() -> { runner.run(command("hang"), directory, request(), Duration.ofSeconds(30), 10000); return null; });
      started(); runner.close();
      assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS)); dead();
      var error = assertThrows(ApiException.class, () -> runner.run(command("echo"), directory, request(), Duration.ofSeconds(10), 10000));
      assertEquals("PDF_PROCESS_UNAVAILABLE", error.code());
    } finally { runner.close(); }
  }

  @Test void oversizedRequestNeverStartsAProcessAndIsCleared() throws Exception {
    byte[] request = new byte[65537]; Arrays.fill(request, (byte) 7);
    try (var runner = new PdfProcessRunner()) {
      var error = assertThrows(ApiException.class, () -> runner.run(command("echo"), directory, request, Duration.ofSeconds(10), 10000));
      assertEquals("PDF_REQUEST_LIMIT", error.code()); assertEquals(400, error.status());
      assertFalse(Files.exists(directory.resolve("child.pid"))); assertArrayEquals(new byte[request.length], request);
    }
  }

  @Test void unavailableExecutableDoesNotExposeItsPath() throws Exception {
    try (var runner = new PdfProcessRunner()) {
      var error = assertThrows(ApiException.class, () -> runner.run(List.of(directory.resolve("missing.exe").toString()), directory,
          request(), Duration.ofSeconds(10), 10000));
      assertEquals("PDF_PROCESS_UNAVAILABLE", error.code()); assertFalse(error.getMessage().contains(directory.toString()));
    }
  }
}
