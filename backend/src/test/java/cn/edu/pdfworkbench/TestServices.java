package cn.edu.pdfworkbench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestServices implements AutoCloseable {
  final String schema = "wb_test_" + UUID.randomUUID().toString().replace("-", "");
  final String namespace = "pdf-workbench:test:" + schema;
  private final Map<String, String> credentials;
  private final HikariDataSource source;
  private boolean closed;

  TestServices() {
    try {
      Path file = Path.of("deploy/.env.local");
      if (!Files.isRegularFile(file)) file = Path.of("../deploy/.env.local");
      credentials = new HashMap<>();
      for (String line : Files.readAllLines(file)) {
        var pair = line.split("=", 2);
        if (pair.length == 2) credentials.put(pair[0], pair[1]);
      }
      try (var connection = DriverManager.getConnection(baseUrl(), "pdf_workbench", password());
          var statement = connection.createStatement()) {
        statement.execute("CREATE SCHEMA " + schema);
      }
      var config = new HikariConfig();
      config.setJdbcUrl(url());
      config.setUsername("pdf_workbench");
      config.setPassword(password());
      config.setMaximumPoolSize(2);
      config.setMinimumIdle(1);
      config.setConnectionTimeout(5000);
      source = new HikariDataSource(config);
      Flyway.configure().dataSource(source).defaultSchema(schema).cleanDisabled(true).load().migrate();
    } catch (Exception e) {
      throw new IllegalStateException("Local PostgreSQL/Redis fixtures require deploy/Start-Local.ps1", e);
    }
  }

  private String baseUrl() {
    return "jdbc:postgresql://127.0.0.1:15432/pdf_workbench";
  }

  String url() { return baseUrl() + "?currentSchema=" + schema; }
  String password() { return credentials.get("POSTGRES_PASSWORD"); }
  JdbcTemplate jdbc() { return new JdbcTemplate(source); }

  void register(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", this::url);
    properties.add("spring.datasource.username", () -> "pdf_workbench");
    properties.add("spring.datasource.password", this::password);
    properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
    properties.add("spring.flyway.default-schema", () -> schema);
    properties.add("spring.data.redis.host", () -> "127.0.0.1");
    properties.add("spring.data.redis.port", () -> 16379);
    properties.add("spring.data.redis.username", () -> "workbench");
    properties.add("spring.data.redis.password", () -> credentials.get("REDIS_PASSWORD"));
    properties.add("spring.session.redis.namespace", () -> namespace);
    properties.add("spring.config.import", () -> "optional:classpath:test-no-local.yml");
    properties.add("ai.api-key", () -> "");
    properties.add("ai.base-url", () -> "http://127.0.0.1:9");
    properties.add("ai.rerank-url", () -> "http://127.0.0.1:9");
  }

  public void close() {
    if (closed) return;
    closed = true;
    source.close();
    if (!schema.matches("wb_test_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema");
    try (var connection = DriverManager.getConnection(baseUrl(), "pdf_workbench", password());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA " + schema + " CASCADE");
    } catch (Exception e) {
      throw new IllegalStateException("Test schema cleanup failed", e);
    }
  }
}
