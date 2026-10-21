package cn.edu.pdfworkbench.storage;

import java.net.URI;
import java.util.Map;
import java.util.Set;

public record S3Settings(String endpoint, String region, String bucket, String accessKey, String secretKey) {
  public S3Settings {
    var uri = URI.create(endpoint);
    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || !Set.of("", "/").contains(uri.getPath())
        || (uri.getScheme().equals("http") && !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())))
      throw new IllegalArgumentException("Invalid S3 endpoint; remote connections require HTTPS");
    if (bucket == null || !bucket.matches("pdf-workbench-[a-z0-9-]{1,48}")
        || region == null || !region.matches("[a-z0-9-]{1,40}"))
      throw new IllegalArgumentException("Invalid S3 bucket or region");
    if (accessKey == null || !accessKey.matches("[\\x21-\\x7e]{1,256}")
        || secretKey == null || !secretKey.matches("[\\x21-\\x7e]{1,256}"))
      throw new IllegalArgumentException("S3 credentials are required");
    endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
  }

  public static S3Settings environment(Map<String, String> values) {
    return new S3Settings(values.getOrDefault("S3_ENDPOINT", "http://127.0.0.1:18333"),
        values.getOrDefault("S3_REGION", "us-east-1"), values.getOrDefault("S3_BUCKET", "pdf-workbench-private"),
        values.get("S3_ACCESS_KEY"), values.get("S3_SECRET_KEY"));
  }

  @Override
  public String toString() { return "S3Settings[bucket=" + bucket + "]"; }
}
