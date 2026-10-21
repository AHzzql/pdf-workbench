package cn.edu.pdfworkbench.storage;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;

class S3ObjectStoreTest {
  @TempDir Path directory;

  private S3Settings local() throws Exception {
    Path file = Path.of("../deploy/.env.local");
    if (!Files.isRegularFile(file)) file = Path.of("deploy/.env.local");
    var env = new HashMap<String, String>();
    for (var line : Files.readAllLines(file)) {
      var pair = line.split("=", 2);
      if (pair.length == 2) env.put(pair[0], pair[1]);
    }
    return S3Settings.environment(env);
  }

  private S3ObjectStore.Key key(S3ObjectStore.Kind kind) {
    return new S3ObjectStore.Key(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), kind);
  }

  private S3ObjectStore.ObjectRef ref(S3ObjectStore.Kind kind, byte[] bytes) throws Exception {
    return new S3ObjectStore.ObjectRef(key(kind), bytes.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
  }

  @Test void privateDeploymentAndIndependentClients() throws Exception {
    var settings = local();
    ObjectStoreProbe.write(settings, directory);
    try { ObjectStoreProbe.verify(settings, directory); }
    finally { ObjectStoreProbe.delete(settings, directory); }
  }

  @Test void pdfTransferUsesIndependentLocalDirectories() throws Exception {
    var source = directory.resolve("source.pdf");
    Files.writeString(source, "%PDF-test-object\n");
    var other = Files.createDirectory(directory.resolve("different-worker-root")).resolve("download.pdf");
    try (var web = new S3ObjectStore(local()); var worker = new S3ObjectStore(local())) {
      var ref = web.inspect(key(S3ObjectStore.Kind.PDF), source);
      try {
        web.put(ref, source);
        worker.get(ref, other);
        assertEquals(-1, Files.mismatch(source, other));
      } finally { worker.delete(ref.key()); }
    }
  }

  @Test void digestAndLengthCorruptionRemoveOnlyPartialDownloads() throws Exception {
    var source = directory.resolve("source.json");
    Files.writeString(source, "{\"text\":\"original\"}");
    var target = directory.resolve("download.json");
    try (var store = new S3ObjectStore(local())) {
      var ref = store.inspect(key(S3ObjectStore.Kind.CONTENT), source);
      try {
        store.put(ref, source);
        var badHash = new S3ObjectStore.ObjectRef(ref.key(), ref.bytes(), "0".repeat(64));
        assertEquals("OBJECT_CORRUPT", assertThrows(ApiException.class, () -> store.get(badHash, target)).code());
        assertFalse(Files.exists(target));
        var badLength = new S3ObjectStore.ObjectRef(ref.key(), ref.bytes() + 1, ref.sha256());
        assertEquals(502, assertThrows(ApiException.class, () -> store.get(badLength, target)).status());
        assertFalse(Files.exists(target));
        Files.writeString(target, "keep-existing");
        assertThrows(FileAlreadyExistsException.class, () -> store.get(ref, target));
        assertEquals("keep-existing", Files.readString(target));
      } finally { store.delete(ref.key()); }
    }
  }

  @Test void storageRejectsIncorrectUploadChecksum() throws Exception {
    var source = directory.resolve("source.json");
    Files.writeString(source, "{\"checksum\":true}");
    try (var store = new S3ObjectStore(local())) {
      var ref = new S3ObjectStore.ObjectRef(key(S3ObjectStore.Kind.CONTENT), Files.size(source), "0".repeat(64));
      try {
        assertThrows(ApiException.class, () -> store.put(ref, source));
        assertEquals(404, assertThrows(ApiException.class, () -> store.getContent(ref)).status());
      } finally { store.delete(ref.key()); }
    }
  }

  @Test void conditionalConcurrentWritesPublishExactlyOneContent() throws Exception {
    var a = Files.writeString(directory.resolve("a"), "first-writer");
    var b = Files.writeString(directory.resolve("b"), "other-writer");
    try (var first = new S3ObjectStore(local()); var second = new S3ObjectStore(local());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int trial = 0; trial < 8; trial++) {
        var key = key(S3ObjectStore.Kind.CONTENT);
        var refA = first.inspect(key, a);
        var refB = second.inspect(key, b);
        var barrier = new CyclicBarrier(2);
        try {
          var one = executor.submit(() -> concurrentPut(first, refA, a, barrier));
          var two = executor.submit(() -> concurrentPut(second, refB, b, barrier));
          int winnerA = one.get(15, TimeUnit.SECONDS), winnerB = two.get(15, TimeUnit.SECONDS);
          assertEquals(1, winnerA + winnerB);
          var winner = winnerA == 1 ? refA : refB;
          assertEquals(winnerA == 1 ? Files.readString(a) : Files.readString(b), new String(first.getContent(winner), StandardCharsets.UTF_8));
        } finally { first.delete(key); }
      }
    }
  }

  private int concurrentPut(S3ObjectStore store, S3ObjectStore.ObjectRef ref, Path source, CyclicBarrier barrier) throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    try { store.put(ref, source); return 1; }
    catch (ApiException e) { if (e.status() == 409) return 0; throw e; }
  }

  @Test void fullSizePdfSurvivesFragmentedStorage() throws Exception {
    var source = directory.resolve("full-size.pdf");
    try (var file = new java.io.RandomAccessFile(source.toFile(), "rw")) { file.setLength(50L * 1024 * 1024); }
    try (var store = new S3ObjectStore(local())) {
      var ref = store.inspect(key(S3ObjectStore.Kind.PDF), source);
      var target = directory.resolve("full-copy.pdf");
      try {
        store.put(ref, source);
        store.get(ref, target);
        assertEquals(-1, Files.mismatch(source, target));
      } finally { store.delete(ref.key()); }
    }
  }

  @Test void equalLengthRemoteReplacementIsNotTrusted() throws Exception {
    var source = directory.resolve("source.json");
    Files.writeString(source, "original");
    var settings = local();
    try (var store = new S3ObjectStore(settings); var client = S3ObjectStore.client(settings)) {
      var ref = store.inspect(key(S3ObjectStore.Kind.CONTENT), source);
      try {
        store.put(ref, source);
        client.putObject(b -> b.bucket(settings.bucket()).key(ref.key().path()), RequestBody.fromString("tampered"));
        assertEquals("OBJECT_CORRUPT", assertThrows(ApiException.class, () -> store.getContent(ref)).code());
      } finally { store.delete(ref.key()); }
    }
  }

  @Test void objectKindsAndManifestsRejectUnboundedInput() throws Exception {
    try (var store = new S3ObjectStore(new S3Settings("http://127.0.0.1:9", "us-east-1", "pdf-workbench-private", "unused", "unused"))) {
      var empty = Files.createFile(directory.resolve("empty"));
      assertEquals(413, assertThrows(ApiException.class, () -> store.inspect(key(S3ObjectStore.Kind.PDF), empty)).status());
      var huge = directory.resolve("huge");
      try (var file = new java.io.RandomAccessFile(huge.toFile(), "rw")) { file.setLength(50L * 1024 * 1024 + 1); }
      assertEquals(413, assertThrows(ApiException.class, () -> store.inspect(key(S3ObjectStore.Kind.PDF), huge)).status());
      assertEquals(413, assertThrows(ApiException.class, () -> store.inspect(key(S3ObjectStore.Kind.CONTENT), huge)).status());
      assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.ObjectRef(key(S3ObjectStore.Kind.CONTENT), 32L * 1024 * 1024 + 1, "0".repeat(64)));
      assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.ObjectRef(key(S3ObjectStore.Kind.PDF), 1, "invalid"));
      assertThrows(IllegalArgumentException.class, () -> store.getContent(new S3ObjectStore.ObjectRef(key(S3ObjectStore.Kind.PDF), 1, "0".repeat(64))));
    }
  }

  @Test void identitiesCannotBecomePaths() {
    String id = UUID.randomUUID().toString();
    for (String invalid : Arrays.asList(null, "../../outside", "a/b", "1-1-1-1-1", id + "/", id.toUpperCase(Locale.ROOT))) {
      assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.Key(invalid, id, id, S3ObjectStore.Kind.PDF));
      assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.Key(id, invalid, id, S3ObjectStore.Kind.PDF));
      assertThrows(IllegalArgumentException.class, () -> new S3ObjectStore.Key(id, id, invalid, S3ObjectStore.Kind.PDF));
    }
  }

  @Test void settingsRequirePrivateTransportAndRedactCredentials() {
    for (String endpoint : List.of("file:///data", "http://external.example", "https://user:secret@example.com", "https://example.com/path", "https://example.com?q=x", "https://example.com/#fragment"))
      assertThrows(IllegalArgumentException.class, () -> new S3Settings(endpoint, "us-east-1", "pdf-workbench-private", "key", "secret"));
    assertThrows(IllegalArgumentException.class, () -> new S3Settings("http://localhost:9", "us-east-1", "outside", "key", "secret"));
    assertThrows(IllegalArgumentException.class, () -> new S3Settings("http://localhost:9", "us-east-1", "pdf-workbench-private", "", "secret"));
    var settings = new S3Settings("https://storage.example/", "us-east-1", "pdf-workbench-private", "private-access", "private-secret");
    assertEquals("https://storage.example", settings.endpoint());
    assertFalse(settings.toString().contains("private-access"));
    assertFalse(settings.toString().contains("private-secret"));
  }

  @Test void sdkDependencyDoesNotReplaceSpringHttpTransport() {
    var factory = new org.springframework.boot.web.client.RestTemplateBuilder().build().getRequestFactory();
    assertEquals("org.springframework.http.client.JdkClientHttpRequestFactory", factory.getClass().getName());
  }

  @Test void missingObjectsAreNotLocalFallbacks() throws Exception {
    var ref = ref(S3ObjectStore.Kind.CONTENT, "missing".getBytes(StandardCharsets.UTF_8));
    try (var store = new S3ObjectStore(local())) {
      var error = assertThrows(ApiException.class, () -> store.getContent(ref));
      assertEquals("OBJECT_MISSING", error.code());
      store.delete(ref.key());
      store.delete(ref.key());
    }
  }

  @Test void serviceErrorsAreBoundedAndDoNotExposeResponses() throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      count.incrementAndGet();
      byte[] body = "<Error><Code>InternalError</Code><Message>private-backend-details</Message></Error>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try (var store = fixture(server)) {
      var error = assertThrows(ApiException.class, () -> store.getContent(ref(S3ObjectStore.Kind.CONTENT, new byte[] {1})));
      assertEquals(503, error.status());
      assertFalse(error.getMessage().contains("private-backend-details"));
      assertTrue(count.get() >= 1 && count.get() <= 2);
    } finally { server.stop(0); }
  }

  @Test void oversizedResponseIsAbortedAndLeavesNoFile() throws Exception {
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 100);
      try (var output = exchange.getResponseBody()) { output.write(new byte[100]); }
    });
    server.start();
    var target = directory.resolve("partial");
    try (var store = fixture(server)) {
      assertEquals(502, assertThrows(ApiException.class, () -> store.get(ref(S3ObjectStore.Kind.PDF, new byte[] {1}), target)).status());
      assertFalse(Files.exists(target));
    } finally { server.stop(0); }
  }

  @Test void redirectsDoNotReceiveSignedRequests() throws Exception {
    var primary = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var redirected = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var leaked = new AtomicInteger();
    redirected.createContext("/", exchange -> { leaked.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
    primary.createContext("/", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + redirected.getAddress().getPort() + "/private");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    primary.start(); redirected.start();
    try (var store = fixture(primary)) {
      assertEquals(503, assertThrows(ApiException.class, () -> store.getContent(ref(S3ObjectStore.Kind.CONTENT, new byte[] {1}))).status());
      assertEquals(0, leaked.get());
    } finally { primary.stop(0); redirected.stop(0); }
  }

  @Test void responseReadTimeoutReleasesTransferAndRemovesPartialFile() throws Exception {
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      try {
        exchange.sendResponseHeaders(200, 1);
        Thread.sleep(6500);
        exchange.getResponseBody().write(1);
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.start();
    var target = directory.resolve("partial");
    long start = System.nanoTime();
    try (var store = fixture(server)) {
      assertEquals(503, assertThrows(ApiException.class, () -> store.get(ref(S3ObjectStore.Kind.PDF, new byte[] {1}), target)).status());
      assertFalse(Files.exists(target));
      assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 12);
    } finally { server.stop(0); executor.shutdownNow(); }
  }

  @Test void truncatedResponseRemovesPartialFile() throws Exception {
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 2);
      try { exchange.getResponseBody().write(1); }
      finally { exchange.close(); }
    });
    server.start();
    var target = directory.resolve("partial");
    try (var store = fixture(server)) {
      assertThrows(ApiException.class, () -> store.get(ref(S3ObjectStore.Kind.PDF, new byte[] {1, 2}), target));
      assertFalse(Files.exists(target));
    } finally { server.stop(0); }
  }

  @Test void inFlightTransfersAreBoundedAndCapacityIsReleased() throws Exception {
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      entered.countDown();
      try {
        release.await(4, TimeUnit.SECONDS);
        exchange.sendResponseHeaders(200, 1);
        exchange.getResponseBody().write(1);
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.start();
    try (var store = fixture(server)) {
      var ref = ref(S3ObjectStore.Kind.CONTENT, new byte[] {1});
      var futures = new ArrayList<Future<byte[]>>();
      for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> store.getContent(ref)));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var error = assertThrows(ApiException.class, () -> store.getContent(ref));
        assertEquals("OBJECT_STORE_BUSY", error.code());
        assertEquals(503, error.status());
      } finally { release.countDown(); }
      for (var future : futures) assertArrayEquals(new byte[] {1}, future.get(5, TimeUnit.SECONDS));
      assertArrayEquals(new byte[] {1}, store.getContent(ref));
    } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
  }

  @Test void interruptedOperationsDoNotStartTransfers() throws Exception {
    var source = Files.writeString(directory.resolve("source"), "test");
    try (var store = new S3ObjectStore(local())) {
      var ref = store.inspect(key(S3ObjectStore.Kind.CONTENT), source);
      Thread.currentThread().interrupt();
      try {
        assertThrows(CancellationException.class, () -> store.put(ref, source));
        assertThrows(CancellationException.class, () -> store.getContent(ref));
        assertThrows(CancellationException.class, () -> store.delete(ref.key()));
      } finally { Thread.interrupted(); }
    }
  }

  private S3ObjectStore fixture(HttpServer server) {
    return new S3ObjectStore(new S3Settings("http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", "pdf-workbench-private", "fixture", "fixture"));
  }
}
