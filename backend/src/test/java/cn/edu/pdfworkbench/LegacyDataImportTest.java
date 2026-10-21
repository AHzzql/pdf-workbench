package cn.edu.pdfworkbench;

import static cn.edu.pdfworkbench.ai.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.auth.AccountRepository;
import cn.edu.pdfworkbench.storage.LegacyDataImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyDataImportTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();
  final String account = UUID.randomUUID().toString();
  final String id = UUID.randomUUID().toString();

  JdbcTemplate legacy(Path path) {
    return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE", "sa", ""));
  }

  void fixture() throws Exception {
    var accounts = root.resolve("accounts");
    Files.createDirectories(accounts);
    legacy(accounts.resolve("account")).execute("CREATE TABLE accounts(id VARCHAR(36) PRIMARY KEY,email VARCHAR(254),display_name VARCHAR(96),password_hash VARCHAR(512),credential_version BIGINT)");
    legacy(accounts.resolve("account")).update("INSERT INTO accounts VALUES(?,?,?,?,?)", account, "legacy@example.com", "Legacy", "encoded-password-fixture", 2);
    var reading = root.resolve("reading/users").resolve(account);
    Files.createDirectories(reading);
    var source = legacy(reading.resolve("history"));
    source.execute("CREATE TABLE ai_documents(id VARCHAR(64) PRIMARY KEY,payload CLOB)");
    source.execute("CREATE TABLE ai_conversations(id VARCHAR(64) PRIMARY KEY,doc_id VARCHAR(64),updated BIGINT,payload CLOB)");
    source.execute("CREATE TABLE ai_messages(id VARCHAR(100) PRIMARY KEY,conversation_id VARCHAR(64),created BIGINT,payload CLOB)");
    source.update("INSERT INTO ai_documents VALUES(?,?)", id, json.writeValueAsString(new Document(id,"legacy.pdf","hash","v1",1,false,"READY","",1)));
    source.update("INSERT INTO ai_conversations VALUES(?,?,?,?)", id, id, 1, json.writeValueAsString(new Conversation(id,id,"History",1,1)));
    source.update("INSERT INTO ai_messages VALUES(?,?,?,?)", id, id, 1, json.writeValueAsString(new Message(id,id,"assistant","partial","RUNNING","","ASK","question",null,null,List.of(),1,3)));
    Files.writeString(reading.resolve(id + ".pdf"), "synthetic PDF fixture");
    Files.writeString(reading.resolve(id + ".json"), "{}");
  }

  @Test
  void migrationPreservesIdentitiesFilesAndCredentialsAndDoesNotOverwriteNewerData() throws Exception {
    fixture();
    try (var services = new TestServices()) {
      var migration = new LegacyDataImport(services.jdbc(), json);
      var counts = migration.importData(root.resolve("accounts"), root.resolve("reading"), root.resolve("target"));
      assertEquals(new LegacyDataImport.Counts(1,1,1,1), counts);
      var accounts = new AccountRepository(services.jdbc());
      var row = accounts.findId(account).orElseThrow();
      assertEquals("encoded-password-fixture", row.hash());
      assertEquals(2, row.version());
      var settings = new AiSettings(root.resolve("target").toString(),"","https://example.invalid","https://example.invalid","chat","embed","rank");
      var reading = new AiRepository(settings,json,services.jdbc());
      try (var scope = reading.scope(account, () -> true)) {
        assertEquals("STOPPED", reading.message(id).status());
        assertEquals(4, reading.message(id).sequence());
        assertEquals("partial", reading.messages(id).getFirst().text());
        assertEquals("synthetic PDF fixture", Files.readString(reading.documentPath(id)));
      }
      assertTrue(accounts.password(row,"updated-encoded-fixture"));
      assertEquals(new LegacyDataImport.Counts(0,0,0,0), migration.importData(root.resolve("accounts"),root.resolve("reading"),root.resolve("target")));
      assertEquals("updated-encoded-fixture", accounts.findId(account).orElseThrow().hash());
      assertTrue(Files.exists(root.resolve("reading/users").resolve(account).resolve(id+".pdf")));
    }
  }

  @Test
  void missingFilesRollBackDatabaseAndMissingSourcesAreRejected() throws Exception {
    fixture();
    Files.delete(root.resolve("reading/users").resolve(account).resolve(id+".pdf"));
    try (var services = new TestServices()) {
      var migration = new LegacyDataImport(services.jdbc(), json);
      assertThrows(IllegalStateException.class, () -> migration.importData(root.resolve("accounts"),root.resolve("reading"),root.resolve("target")));
      assertEquals(0, services.jdbc().queryForObject("SELECT COUNT(*) FROM accounts",Integer.class));
      assertThrows(IllegalStateException.class, () -> migration.importData(root.resolve("missing"),root.resolve("missing"),root.resolve("target")));
    }
  }
}
