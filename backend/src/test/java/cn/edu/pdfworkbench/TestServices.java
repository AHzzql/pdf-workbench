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
  private cn.edu.pdfworkbench.storage.S3ObjectStore objects;

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
  cn.edu.pdfworkbench.storage.S3Settings objectSettings() {
    return cn.edu.pdfworkbench.storage.S3Settings.environment(credentials);
  }
  cn.edu.pdfworkbench.ai.ElasticsearchSettings searchSettings() {
    return new cn.edu.pdfworkbench.ai.ElasticsearchSettings("http://127.0.0.1:19200", "elastic",
        credentials.get("ELASTIC_PASSWORD"), "pdf-workbench-test-" + schema.substring("wb_test_".length()));
  }
  String taskNamespace() { return "pdf-workbench.test." + schema.substring("wb_test_".length()); }
  org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbit() {
    var factory = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",5672);
    factory.setUsername("workbench"); factory.setPassword(credentials.get("RABBITMQ_PASSWORD")); factory.setVirtualHost("workbench");
    factory.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
    factory.setPublisherReturns(true);
    return factory;
  }
  JdbcTemplate jdbc() { return new JdbcTemplate(source); }
  synchronized cn.edu.pdfworkbench.storage.S3ObjectStore objects() {
    if (objects==null) objects=new cn.edu.pdfworkbench.storage.S3ObjectStore(objectSettings());
    return objects;
  }
  cn.edu.pdfworkbench.storage.DocumentStorage files() {
    return new cn.edu.pdfworkbench.storage.DocumentStorage(jdbc(),objects());
  }

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
    properties.add("tasks.namespace", this::taskNamespace);
    properties.add("spring.rabbitmq.password", () -> credentials.get("RABBITMQ_PASSWORD"));
    properties.add("search.password", () -> searchSettings().password());
    properties.add("search.alias", () -> searchSettings().alias());
    properties.add("storage.access-key", () -> credentials.get("S3_ACCESS_KEY"));
    properties.add("storage.secret-key", () -> credentials.get("S3_SECRET_KEY"));
    properties.add("spring.config.import", () -> "optional:classpath:test-no-local.yml");
    properties.add("ai.api-key", () -> "");
    properties.add("ai.base-url", () -> "http://127.0.0.1:9");
    properties.add("ai.rerank-url", () -> "http://127.0.0.1:9");
  }

  public void close() {
    if (closed) return;
    closed = true;
    if (!schema.matches("wb_test_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema");
    try {
      var rows=jdbc().query("SELECT workspace_id::text,document_id::text,generation::text FROM document_files",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)});
      for (var row : rows) for (var kind : cn.edu.pdfworkbench.storage.S3ObjectStore.Kind.values())
        objects().delete(new cn.edu.pdfworkbench.storage.S3ObjectStore.Key(row[0],row[1],row[2],kind));
    } finally { if (objects!=null) objects.close(); }
    source.close();
    var rabbit = rabbit();
    try {
      var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit);
      for (String route : List.of("reading","index","cleanup","dead")) admin.deleteQueue(taskNamespace()+"."+route);
      admin.deleteExchange(taskNamespace());
    } finally { rabbit.destroy(); }
    var search = searchSettings();
    if (!search.physicalIndex().matches("pdf-workbench-test-[a-f0-9]{32}-v1"))
      throw new IllegalStateException("Invalid test index");
    try {
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(search.url() + "/" + search.physicalIndex()))
          .timeout(java.time.Duration.ofSeconds(10)).header("Authorization", "Basic " + Base64.getEncoder()
              .encodeToString((search.username() + ":" + search.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .DELETE().build();
      var result = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
      if (result.statusCode() != 200 && result.statusCode() != 404) throw new IllegalStateException("Test index cleanup failed");
    } catch (Exception e) { throw new IllegalStateException("Test index cleanup failed", e); }
    if (!schema.matches("wb_test_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema");
    try (var connection = DriverManager.getConnection(baseUrl(), "pdf_workbench", password());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA " + schema + " CASCADE");
    } catch (Exception e) {
      throw new IllegalStateException("Test schema cleanup failed", e);
    }
  }
}
