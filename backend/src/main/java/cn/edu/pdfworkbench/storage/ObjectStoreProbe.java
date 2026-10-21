package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 本机部署探针；随机对象标识与业务文档无关。 */
public final class ObjectStoreProbe {
  private static final byte[] SAMPLE = "private-storage-integrity-probe\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  public static void main(String[] args) {
    if (args.length != 2 || !Set.of("write", "verify", "delete", "missing").contains(args[0])) {
      System.err.println("Usage: ObjectStoreProbe <write|verify|delete|missing> <probe-directory>");
      System.exit(2);
    }
    try {
      var settings = S3Settings.environment(System.getenv());
      var directory = Path.of(args[1]);
      switch (args[0]) {
        case "write" -> write(settings, directory);
        case "verify" -> verify(settings, directory);
        case "delete" -> delete(settings, directory);
        case "missing" -> missing(settings, directory);
        default -> throw new IllegalArgumentException();
      }
      System.out.println("PASS private object storage " + args[0]);
    } catch (Exception e) {
      System.err.println("OBJECT_STORAGE_PROBE_FAILED");
      System.exit(1);
    }
  }

  static void write(S3Settings settings, Path directory) throws Exception {
    Files.createDirectories(directory);
    var key = new S3ObjectStore.Key(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), S3ObjectStore.Kind.CONTENT);
    var source = directory.resolve("sample.json");
    Files.write(source, SAMPLE, StandardOpenOption.CREATE_NEW);
    try (var store = new S3ObjectStore(settings)) {
      var ref = store.inspect(key, source);
      // 上传前留下清理标识，确认丢失仍可删除本次随机探针对象。
      new ObjectMapper().writeValue(directory.resolve("manifest.json").toFile(), ref);
      store.put(ref, source);
    }
  }

  static void verify(S3Settings settings, Path directory) throws Exception {
    var ref = manifest(directory);
    try (var store = new S3ObjectStore(settings)) {
      if (!Arrays.equals(SAMPLE, store.getContent(ref))) throw new IllegalStateException("Probe content differs");
      try {
        store.put(ref, directory.resolve("sample.json"));
        throw new IllegalStateException("Conditional put overwrote an object");
      } catch (ApiException e) { if (e.status() != 409) throw e; }
      if (!Arrays.equals(SAMPLE, store.getContent(ref))) throw new IllegalStateException("Object was changed");
    }
    permissions(settings, ref.key());
  }

  static void permissions(S3Settings settings, S3ObjectStore.Key key) throws Exception {
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(settings.endpoint() + "/" + settings.bucket() + "/" + key.path()))
        .timeout(java.time.Duration.ofSeconds(5)).GET().build();
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      if (client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() != 403)
        throw new IllegalStateException("Anonymous object access was allowed");
    }
    try (var client = S3ObjectStore.client(settings)) {
      var other = "pdf-workbench-denied-" + UUID.randomUUID().toString().substring(0, 8);
      denied(() -> client.createBucket(b -> b.bucket(other)));
      denied(() -> client.getObject(b -> b.bucket(other).key(key.path())));
      denied(() -> client.putBucketPolicy(b -> b.bucket(settings.bucket()).policy("{")));
    }
    var invalid = new S3Settings(settings.endpoint(), settings.region(), settings.bucket(), settings.accessKey(), UUID.randomUUID().toString());
    try (var client = S3ObjectStore.client(invalid)) {
      denied(() -> client.headObject(b -> b.bucket(settings.bucket()).key(key.path())));
    }
  }

  private static void denied(Supplier<?> request) {
    try { request.get(); }
    catch (S3Exception e) { if (e.statusCode() == 403) return; throw e; }
    throw new IllegalStateException("Private storage permission was not enforced");
  }

  static void delete(S3Settings settings, Path directory) throws Exception {
    var ref = manifest(directory);
    try (var store = new S3ObjectStore(settings)) {
      store.delete(ref.key());
      store.delete(ref.key());
      try {
        store.getContent(ref);
        throw new IllegalStateException("Deleted object remains readable");
      } catch (ApiException e) { if (e.status() != 404) throw e; }
    }
  }

  private static S3ObjectStore.ObjectRef manifest(Path directory) throws java.io.IOException {
    return new ObjectMapper().readValue(directory.resolve("manifest.json").toFile(), S3ObjectStore.ObjectRef.class);
  }

  private static void missing(S3Settings settings, Path directory) throws Exception {
    try (var store = new S3ObjectStore(settings)) {
      try { store.getContent(manifest(directory)); }
      catch (ApiException e) { if (e.status()==404) return; throw e; }
      throw new IllegalStateException("Object remains readable");
    }
  }
}
