package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.ai.AiRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class LegacyDataImport {
  public record Counts(int accounts, int documents, int conversations, int messages) {}
  private final JdbcTemplate target;
  private final ObjectMapper json;

  public LegacyDataImport(JdbcTemplate target, ObjectMapper json) {
    this.target = target;
    this.json = json;
  }

  public Counts importData(Path accountSource, Path aiSource, Path fileTarget) {
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(target.getDataSource())));
    return transaction.execute(status -> {
      int[] counts = new int[4];
      boolean present = false;
      if (Files.isRegularFile(accountSource.resolve("account.mv.db"))) {
        present = true;
        source(accountSource.resolve("account")).query("SELECT * FROM accounts", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
          String id = row.getString("id");
          AiRepository.validateId(id);
          String email = row.getString("email");
          int added = target.update("INSERT INTO accounts(id,email,display_name,password_hash,credential_version)"
              + " VALUES(?::uuid,?,?,?,?) ON CONFLICT DO NOTHING", id, email, row.getString("display_name"),
              row.getString("password_hash"), row.getLong("credential_version"));
          if (added == 0 && target.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?::uuid AND email=?", Integer.class, id, email) != 1)
            throw new IllegalStateException("LEGACY_ACCOUNT_CONFLICT");
          counts[0] += added;
        });
      }
      if (Files.isRegularFile(aiSource.resolve("history.mv.db"))) {
        present = true;
        String quarantine = UUID.nameUUIDFromBytes(("legacy-quarantine:" + aiSource.toAbsolutePath().normalize()).getBytes(StandardCharsets.UTF_8)).toString();
        workspace(aiSource, fileTarget, quarantine, counts);
      }
      Path users = aiSource.resolve("users");
      if (Files.isDirectory(users)) {
        try (var directories = Files.list(users)) {
          for (var directory : directories.filter(Files::isDirectory).toList()) {
            String owner = directory.getFileName().toString();
            AiRepository.validateId(owner);
            if (Files.isRegularFile(directory.resolve("history.mv.db"))) {
              present = true;
              workspace(directory, fileTarget, owner, counts);
            }
          }
        } catch (java.io.IOException e) { throw new IllegalStateException("LEGACY_STORAGE_UNAVAILABLE"); }
      }
      if (!present) throw new IllegalStateException("LEGACY_DATABASE_MISSING");
      return new Counts(counts[0], counts[1], counts[2], counts[3]);
    });
  }

  private JdbcTemplate source(Path file) {
    return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + file.toAbsolutePath().normalize().toString().replace('\\', '/')
        + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", "sa", ""));
  }

  private void workspace(Path directory, Path fileTarget, String owner, int[] counts) {
    var source = source(directory.resolve("history"));
    source.query("SELECT id,payload FROM ai_documents", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      var node = payload(row.getString("id"), row.getString("payload"));
      copy(directory, fileTarget, owner, node.path("id").asText());
      counts[1] += target.update("INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,?::jsonb)"
          + " ON CONFLICT DO NOTHING", owner, node.path("id").asText(), node.toString());
    });
    source.query("SELECT id,doc_id,updated,payload FROM ai_conversations", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      var node = payload(row.getString("id"), row.getString("payload"));
      AiRepository.validateId(row.getString("doc_id"));
      if (!row.getString("doc_id").equals(node.path("documentId").asText())) throw new IllegalStateException("LEGACY_RECORD_INVALID");
      counts[2] += target.update("INSERT INTO ai_conversations(workspace_id,id,doc_id,updated,payload)"
          + " VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb) ON CONFLICT DO NOTHING",
          owner, row.getString("id"), row.getString("doc_id"), row.getLong("updated"), node.toString());
    });
    source.query("SELECT id,conversation_id,created,payload FROM ai_messages", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      var node = payload(row.getString("id"), row.getString("payload"));
      AiRepository.validateId(row.getString("conversation_id"));
      if (!row.getString("conversation_id").equals(node.path("conversationId").asText())) throw new IllegalStateException("LEGACY_RECORD_INVALID");
      if (node.path("status").asText().equals("RUNNING")) {
        node.put("status", "STOPPED");
        node.put("error", "历史生成任务已中断");
        node.put("sequence", node.path("sequence").asLong() + 1);
      }
      counts[3] += target.update("INSERT INTO ai_messages(workspace_id,id,conversation_id,created,payload)"
          + " VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb) ON CONFLICT DO NOTHING",
          owner, row.getString("id"), row.getString("conversation_id"), row.getLong("created"), node.toString());
    });
  }

  private ObjectNode payload(String id, String value) {
    AiRepository.validateId(id);
    try {
      var node = json.readTree(value);
      if (!(node instanceof ObjectNode object) || !id.equals(node.path("id").asText()))
        throw new IllegalStateException("LEGACY_RECORD_INVALID");
      return object;
    } catch (java.io.IOException e) { throw new IllegalStateException("LEGACY_RECORD_INVALID"); }
  }

  private void copy(Path source, Path target, String owner, String id) {
    var directory = target.toAbsolutePath().normalize().resolve("users").resolve(owner);
    try {
      Files.createDirectories(directory);
      for (String suffix : List.of(".pdf", ".json")) {
        var input = source.resolve(id + suffix);
        var output = directory.resolve(id + suffix);
        if (!Files.isRegularFile(input)) throw new IllegalStateException("LEGACY_FILE_MISSING");
        if (!Files.exists(output)) Files.copy(input, output);
        else if (suffix.equals(".pdf") && Files.mismatch(input, output) != -1)
          throw new IllegalStateException("LEGACY_FILE_CONFLICT");
      }
    } catch (java.io.IOException e) { throw new IllegalStateException("LEGACY_FILE_COPY_FAILED"); }
  }

  public static void main(String[] args) {
    if (args.length != 3) {
      System.err.println("Usage: LegacyDataImport <account-source> <reading-source> <file-target>");
      System.exit(2);
    }
    try {
      var env = System.getenv();
      var data = new DriverManagerDataSource(env.getOrDefault("DATABASE_URL", "jdbc:postgresql://127.0.0.1:15432/pdf_workbench"),
          env.getOrDefault("DATABASE_USER", "pdf_workbench"), env.getOrDefault("DATABASE_PASSWORD", env.get("POSTGRES_PASSWORD")));
      Flyway.configure().dataSource(data).cleanDisabled(true).load().migrate();
      var result = new LegacyDataImport(new JdbcTemplate(data), new ObjectMapper()).importData(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
      System.out.printf("Imported accounts=%d documents=%d conversations=%d messages=%d%n", result.accounts(), result.documents(), result.conversations(), result.messages());
    } catch (Exception e) {
      System.err.println("LEGACY_IMPORT_FAILED");
      System.exit(1);
    }
  }
}
