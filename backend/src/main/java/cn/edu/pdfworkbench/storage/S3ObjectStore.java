package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** 私有文件传输；对象所有权和发布状态由 PostgreSQL 清单约束。 */
public final class S3ObjectStore implements AutoCloseable {
  public enum Kind {
    PDF("source.pdf", "application/pdf", 50L * 1024 * 1024),
    CONTENT("content.json", "application/json", 32L * 1024 * 1024);
    final String filename;
    final String contentType;
    final long limit;
    Kind(String filename, String contentType, long limit) {
      this.filename = filename; this.contentType = contentType; this.limit = limit;
    }
  }

  public record Key(String workspace, String document, String generation, Kind kind) {
    public Key {
      for (var value : new String[] {workspace, document, generation})
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
          throw new IllegalArgumentException("Invalid object identity");
      Objects.requireNonNull(kind);
    }
    public String path() {
      return "v1/workspaces/" + workspace + "/documents/" + document + "/" + generation + "/" + kind.filename;
    }
  }

  public record ObjectRef(Key key, long bytes, String sha256) {
    public ObjectRef {
      Objects.requireNonNull(key);
      if (bytes < 1 || bytes > key.kind.limit || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid object manifest");
    }
  }

  private final S3Client client;
  private final String bucket;
  private final Semaphore transfers = new Semaphore(4);

  public S3ObjectStore(S3Settings settings) {
    this.client = client(settings);
    this.bucket = settings.bucket();
  }

  public String bucket() { return bucket; }

  static S3Client client(S3Settings settings) {
    return S3Client.builder().endpointOverride(java.net.URI.create(settings.endpoint()))
        .region(Region.of(settings.region()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
            .socketTimeout(Duration.ofSeconds(5)).proxyConfiguration(p -> p.useSystemPropertyValues(false).useEnvironmentVariablesValues(false)))
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(30))
            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build()))
        .build();
  }

  public ObjectRef inspect(Key key, Path source) throws IOException {
    long size = Files.size(source);
    if (size < 1 || size > key.kind.limit) throw new ApiException(413, "OBJECT_SIZE_LIMIT", "文件大小超过存储限制");
    var digest = digest();
    try (var input = Files.newInputStream(source)) {
      byte[] buffer = new byte[8192];
      long total = 0;
      for (int n; (n = input.read(buffer)) != -1;) {
        checkInterrupted();
        total += n;
        if (total > size) throw new IOException("Source changed during inspection");
        digest.update(buffer, 0, n);
      }
      if (total != size) throw new IOException("Source changed during inspection");
    }
    return new ObjectRef(key, size, HexFormat.of().formatHex(digest.digest()));
  }

  public void put(ObjectRef ref, Path source) throws IOException {
    if (Files.size(source) != ref.bytes) throw new IOException("Source length differs from manifest");
    acquire();
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(ref.key.path())
          .contentLength(ref.bytes).contentType(ref.key.kind.contentType).ifNoneMatch("*")
          .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(ref.sha256))).build(), RequestBody.fromFile(source));
    } catch (S3Exception e) {
      if (e.statusCode() == 412 || e.statusCode() == 409)
        throw new ApiException(409, "OBJECT_EXISTS", "文件已存在，不能覆盖");
      throw unavailable();
    } catch (SdkException e) { checkInterrupted(); throw unavailable(); }
    finally { transfers.release(); }
  }

  /** 目标文件须不存在；校验失败时只删除本次创建的部分副本。 */
  public void get(ObjectRef ref, Path destination) throws IOException {
    boolean created = false;
    boolean complete = false;
    try {
      try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        created = true;
        read(ref, output);
      }
      complete = true;
    } finally {
      if (created && !complete) Files.deleteIfExists(destination);
    }
  }

  public byte[] getContent(ObjectRef ref) throws IOException {
    if (ref.key.kind != Kind.CONTENT) throw new IllegalArgumentException("Content object required");
    var output = new ByteArrayOutputStream((int) Math.min(ref.bytes, 65536));
    read(ref, output);
    return output.toByteArray();
  }

  private void read(ObjectRef ref, OutputStream output) throws IOException {
    acquire();
    ResponseInputStream<GetObjectResponse> input = null;
    boolean complete = false;
    try {
      input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(ref.key.path()).build());
      if (!Objects.equals(input.response().contentLength(), ref.bytes)) throw corrupt();
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      var digest = digest();
      byte[] buffer = new byte[8192];
      long total = 0;
      for (int n; (n = input.read(buffer)) != -1;) {
        checkInterrupted();
        if (System.nanoTime() > deadline) throw unavailable();
        total += n;
        if (total > ref.bytes) throw corrupt();
        digest.update(buffer, 0, n);
        output.write(buffer, 0, n);
      }
      if (total != ref.bytes || !HexFormat.of().formatHex(digest.digest()).equals(ref.sha256)) throw corrupt();
      complete = true;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) throw new ApiException(404, "OBJECT_MISSING", "持久文件不存在");
      throw unavailable();
    } catch (SdkException | IOException e) { checkInterrupted(); throw unavailable(); }
    finally {
      try {
        if (input != null) {
          if (!complete) input.abort();
          else input.close();
        }
      } finally { transfers.release(); }
    }
  }

  public void delete(Key key) {
    acquire();
    try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.path()).build()); }
    catch (SdkException e) { checkInterrupted(); throw unavailable(); }
    finally { transfers.release(); }
  }

  private void acquire() {
    checkInterrupted();
    if (!transfers.tryAcquire()) throw new ApiException(503, "OBJECT_STORE_BUSY", "文件传输繁忙，请稍后重试");
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
  }

  private static MessageDigest digest() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private static ApiException unavailable() { return new ApiException(503, "OBJECT_STORE_UNAVAILABLE", "文件存储暂不可用"); }
  private static ApiException corrupt() { return new ApiException(502, "OBJECT_CORRUPT", "持久文件完整性校验失败"); }
  public void close() { client.close(); }
}
