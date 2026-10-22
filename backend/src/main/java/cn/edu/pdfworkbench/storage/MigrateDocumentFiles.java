package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.util.Identifiers;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 将旧持久目录发布到私有对象存储，保留源文件与业务标识。 */
public final class MigrateDocumentFiles {
  public record Counts(int migrated, int skipped) {}
  private record Row(String workspace, String document, String payload) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final DocumentStorage files;

  public MigrateDocumentFiles(JdbcTemplate jdbc, ObjectMapper json, DocumentStorage files) {
    this.jdbc=jdbc; this.json=json; this.files=files;
  }

  public Counts migrate(Path root) throws IOException {
    Path base = root.toRealPath();
    String workspace = null, document = null;
    int migrated = 0, skipped = 0;
    while (true) {
      var batch = jdbc.query("SELECT workspace_id::text,id::text,payload::text FROM ai_documents WHERE payload->>'status' IS DISTINCT FROM 'DELETING'"
          + " AND (?::uuid IS NULL OR (workspace_id,id)>(?::uuid,?::uuid)) ORDER BY workspace_id,id LIMIT 64",
          (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3)),workspace,workspace,document);
      if (batch.isEmpty()) return new Counts(migrated,skipped);
      for (var row : batch) {
        workspace=row.workspace; document=row.document;
        Identifiers.requireUuid(workspace); Identifiers.requireUuid(document);
        var value = json.readValue(row.payload,Document.class);
        if (!document.equals(value.id()) || value.hash()==null || !value.hash().matches("[a-f0-9]{64}")
            || value.pages()<1 || value.pages()>200 || value.status()==null)
          throw new IOException("Invalid legacy document record");
        try { files.ready(workspace,document); skipped++; continue; }
        catch (ApiException e) { if (!e.code().equals("FILES_NOT_PUBLISHED")) throw e; }
        Path pdf = source(base,workspace,document,".pdf");
        Path content = source(base,workspace,document,".json");
        if (Files.size(pdf)<5 || Files.size(pdf)>50L*1024*1024) throw new IOException("Invalid legacy PDF size");
        try (var input = Files.newInputStream(pdf)) {
          if (!Arrays.equals(input.readNBytes(5),"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("Invalid legacy PDF header");
        }
        var extracted = readContent(content,value.pages());
        Path snapshot = Files.createTempFile("pdf-content-migration-",".json");
        DocumentStorage.Manifest manifest = null;
        try {
          json.writeValue(snapshot.toFile(),extracted);
          manifest = files.stage(workspace,document,value.hash(),pdf,snapshot);
          files.upload(manifest,pdf,snapshot);
          files.publish(manifest,()->{});
          migrated++;
        } catch (IOException | RuntimeException e) {
          if (manifest!=null) {
            try { files.abandon(manifest); }
            catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
          }
          throw e;
        } finally { Files.deleteIfExists(snapshot); }
      }
    }
  }

  private Path source(Path base, String workspace, String document, String suffix) throws IOException {
    Path path = base.resolve("users").resolve(workspace).resolve(document+suffix).toRealPath();
    if (!path.startsWith(base) || !Files.isRegularFile(path)) throw new IOException("Invalid legacy file path");
    return path;
  }

  private Content readContent(Path path, int pages) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) { bytes=input.readNBytes(32*1024*1024+1); }
    if (bytes.length==0 || bytes.length>32*1024*1024) throw new IOException("Invalid legacy content size");
    var value = json.readValue(bytes,Content.class);
    if (value==null || value.chunks()==null || value.chapters()==null || value.chunks().isEmpty() || value.chunks().size()>4000
        || value.chapters().size()>4000) throw new IOException("Invalid legacy content structure");
    long characters=0;
    var ids = new HashSet<String>();
    for (var chunk : value.chunks()) {
      if (chunk==null || chunk.id()==null || chunk.text()==null || chunk.page()<1 || chunk.page()>pages
          || chunk.start()<0 || chunk.end()<chunk.start() || chunk.end()-chunk.start()!=chunk.text().length()
          || chunk.text().length()>1200 || !chunk.id().equals("p"+chunk.page()+"-"+chunk.start())
          || !ids.add(chunk.id()) || chunk.bounds()==null) throw new IOException("Invalid legacy content chunk");
      for (var bound : chunk.bounds()) if (bound==null || !Double.isFinite(bound.x()) || !Double.isFinite(bound.y())
          || !Double.isFinite(bound.width()) || !Double.isFinite(bound.height()) || bound.width()<0 || bound.height()<0)
        throw new IOException("Invalid legacy content bounds");
      characters+=chunk.text().length();
    }
    ids.clear();
    for (var chapter : value.chapters()) if (chapter==null || chapter.id()==null || chapter.id().isBlank() || !ids.add(chapter.id())
        || chapter.title()==null || chapter.source()==null || chapter.page()<1 || chapter.page()>pages
        || chapter.endPage()<chapter.page() || chapter.endPage()>pages || chapter.offset()<0 || chapter.endOffset()<0
        || (chapter.endPage()==chapter.page() && chapter.endOffset()<chapter.offset()))
      throw new IOException("Invalid legacy chapter");
    if (characters>4_800_000) throw new IOException("Invalid legacy content length");
    return value;
  }

  public static void main(String[] args) {
    if (args.length!=1) {
      System.err.println("Usage: MigrateDocumentFiles <source-directory>"); System.exit(2);
    }
    try {
      var env = System.getenv();
      var data = new DriverManagerDataSource(env.getOrDefault("DATABASE_URL","jdbc:postgresql://127.0.0.1:15432/pdf_workbench"),
          env.getOrDefault("DATABASE_USER","pdf_workbench"),env.getOrDefault("DATABASE_PASSWORD",env.get("POSTGRES_PASSWORD")));
      String schema;
      try (var connection = data.getConnection()) { schema=connection.getSchema(); }
      if (schema==null || !schema.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalStateException("Existing database schema required");
      Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).cleanDisabled(true).load().migrate();
      var jdbc = new JdbcTemplate(data);
      try (var objects = new S3ObjectStore(S3Settings.environment(env))) {
        var result = new MigrateDocumentFiles(jdbc,new ObjectMapper(),new DocumentStorage(jdbc,objects)).migrate(Path.of(args[0]));
        System.out.printf("Migrated=%d skipped=%d%n",result.migrated,result.skipped);
      }
    } catch (Exception e) { System.err.println("DOCUMENT_FILE_MIGRATION_FAILED"); System.exit(1); }
  }
}
