package cn.edu.pdfworkbench.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class StorageConfiguration {
  @Bean S3ObjectStore objectStore(@Value("${storage.endpoint}") String endpoint,
      @Value("${storage.region}") String region, @Value("${storage.bucket}") String bucket,
      @Value("${storage.access-key}") String accessKey, @Value("${storage.secret-key}") String secretKey) {
    return new S3ObjectStore(new S3Settings(endpoint,region,bucket,accessKey,secretKey));
  }
  @Bean DocumentStorage documentStorage(JdbcTemplate jdbc, S3ObjectStore objects) {
    return new DocumentStorage(jdbc,objects);
  }
}
