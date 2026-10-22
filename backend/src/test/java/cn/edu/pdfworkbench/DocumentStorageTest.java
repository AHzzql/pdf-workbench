package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.pdfworkbench.ai.AiTypes.Document;
import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentStorageTest {
  @TempDir Path root;
  TestServices database;
  S3ObjectStore objects;
  DocumentStorage files;
  record Source(String workspace, String document, Path pdf, Path content, String hash) {}

  @BeforeEach void start() {
    database = new TestServices();
    objects = new S3ObjectStore(database.objectSettings());
    files = new DocumentStorage(database.jdbc(),objects);
  }

  @AfterEach void close() {
    try {
      if (database != null && objects != null) {
        var keys = database.jdbc().query("SELECT workspace_id::text,document_id::text,generation::text FROM document_files",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)});
        for (var key : keys) for (var kind : S3ObjectStore.Kind.values()) objects.delete(new S3ObjectStore.Key(key[0],key[1],key[2],kind));
      }
    } finally { if (objects != null) objects.close(); if (database != null) database.close(); }
  }

  Source source(String text) throws Exception {
    return source(UUID.randomUUID().toString(),UUID.randomUUID().toString(),text);
  }

  Source source(String workspace, String document, String text) throws Exception {
    var directory = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
    var pdf = Files.writeString(directory.resolve("source.pdf"),"%PDF-test-"+text);
    var content = Files.writeString(directory.resolve("content.json"),new ObjectMapper().writeValueAsString(Map.of("text",text)));
    var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf)));
    return new Source(workspace,document,pdf,content,hash);
  }

  DocumentStorage.Manifest stage(Source source) throws Exception {
    return files.stage(source.workspace,source.document,source.hash,source.pdf,source.content);
  }

  DocumentStorage.Manifest uploaded(Source source) throws Exception {
    var value = stage(source); files.upload(value,source.pdf,source.content); return value;
  }

  void business(Source source) {
    try {
      var value = new Document(source.document,"sample.pdf",source.hash,"test",1,false,"PARSED","",1);
      database.jdbc().update("INSERT INTO ai_documents(workspace_id,id,payload) VALUES(?::uuid,?::uuid,?::jsonb)"
          + " ON CONFLICT(workspace_id,id) DO UPDATE SET payload=excluded.payload",source.workspace,source.document,new ObjectMapper().writeValueAsString(value));
    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
  }

  void tombstone(Source source) {
    database.jdbc().update("UPDATE ai_documents SET payload=jsonb_set(payload,'{status}','\"DELETING\"') WHERE workspace_id=?::uuid AND id=?::uuid",source.workspace,source.document);
  }

  String state(DocumentStorage.Manifest manifest) {
    return database.jdbc().queryForObject("SELECT state FROM document_files WHERE generation=?::uuid",String.class,manifest.generation());
  }

  void expire(DocumentStorage.Manifest manifest) {
    database.jdbc().update("UPDATE document_files SET lease_until=clock_timestamp()-interval '1 second' WHERE generation=?::uuid",manifest.generation());
  }

  @Test void onlyVerifiedFilesAndBusinessRecordsPublishTogether() throws Exception {
    var source = source("publication"); var manifest = stage(source);
    assertEquals(409,assertThrows(ApiException.class,()->files.publish(manifest,()->business(source))).status());
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM ai_documents",Integer.class));
    files.upload(manifest,source.pdf,source.content);
    assertThrows(IllegalStateException.class,()->files.publish(manifest,()->{ business(source); throw new IllegalStateException("rollback"); }));
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM ai_documents",Integer.class));
    assertEquals("STAGING",state(manifest));
    assertTrue(files.publish(manifest,()->business(source)));
    assertEquals("READY",state(manifest));
    assertFalse(files.publish(manifest,()->fail("Idempotent publish repeated the business callback")));
    assertArrayEquals(Files.readAllBytes(source.content),files.content(source.workspace,source.document));
    files.abandon(manifest);
    assertEquals("READY",state(manifest));
    assertEquals(0,files.reap(4));
  }

  @Test void publishRequiresMatchingBusinessDocument() throws Exception {
    var source = source("consistency"); var manifest = uploaded(source);
    assertEquals("DOCUMENT_NOT_PUBLISHED",assertThrows(ApiException.class,()->files.publish(manifest,()->{})).code());
    var other = source(source.workspace,source.document,"different");
    assertEquals("DOCUMENT_FILE_MISMATCH",assertThrows(ApiException.class,()->files.publish(manifest,()->business(other))).code());
    assertEquals("STAGING",state(manifest));
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM ai_documents",Integer.class));
    files.publish(manifest,()->business(source));
    business(other);
    assertEquals("DOCUMENT_FILE_MISMATCH",assertThrows(ApiException.class,()->files.content(source.workspace,source.document)).code());
  }

  @Test void independentRootsReadPublishedFilesAndCrossSpaceAccessFailsBeforeS3() throws Exception {
    var source = source("isolated"); var manifest = uploaded(source);
    files.publish(manifest,()->business(source));
    var workerDirectory = Files.createDirectory(root.resolve("independent-worker"));
    try (var client = new S3ObjectStore(database.objectSettings())) {
      var worker = new DocumentStorage(database.jdbc(),client);
      worker.pdf(source.workspace,source.document,workerDirectory.resolve("copy.pdf"));
      assertEquals(-1,Files.mismatch(source.pdf,workerDirectory.resolve("copy.pdf")));
      assertArrayEquals(Files.readAllBytes(source.content),worker.content(source.workspace,source.document));
    }
    try (var unavailable = offline()) {
      var other = new DocumentStorage(database.jdbc(),unavailable);
      assertEquals(404,assertThrows(ApiException.class,()->other.content(UUID.randomUUID().toString(),source.document)).status());
      assertEquals(404,assertThrows(ApiException.class,()->other.pdf(UUID.randomUUID().toString(),source.document,root.resolve("forbidden"))).status());
      assertFalse(Files.exists(root.resolve("forbidden")));
      assertEquals(503,assertThrows(ApiException.class,()->other.content(source.workspace,source.document)).status());
      assertTrue(Files.exists(source.content));
    }
  }

  @Test void uploadCanResumeAfterLostAcknowledgementWithoutOverwriting() throws Exception {
    var source = source("retry"); var manifest = stage(source);
    objects.put(manifest.pdf(),source.pdf);
    files.upload(manifest,source.pdf,source.content);
    files.upload(manifest,source.pdf,source.content);
    files.publish(manifest,()->business(source));
    assertArrayEquals(Files.readAllBytes(source.content),files.content(source.workspace,source.document));
  }

  @Test void expiredOrAbandonedUploadsCannotPublishAndAreReconciledAfterLateWrites() throws Exception {
    var source = source("expired"); var manifest = uploaded(source);
    assertEquals(0,files.reap(4));
    expire(manifest);
    assertEquals(1,files.reap(4));
    assertEquals("GARBAGE",state(manifest));
    assertEquals(409,assertThrows(ApiException.class,()->files.publish(manifest,()->business(source))).status());
    assertEquals(409,assertThrows(ApiException.class,()->files.upload(manifest,source.pdf,source.content)).status());
    assertEquals(404,assertThrows(ApiException.class,()->objects.getContent(manifest.content())).status());
    objects.put(manifest.content(),source.content);
    database.jdbc().update("UPDATE document_files SET cleanup_at=clock_timestamp() WHERE generation=?::uuid",manifest.generation());
    assertEquals(1,files.reap(4));
    assertEquals(404,assertThrows(ApiException.class,()->objects.getContent(manifest.content())).status());
    assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='GARBAGE'",Integer.class));
  }

  @Test void deletingDocumentRetiresExactGenerationsWithoutTouchingNewFiles() throws Exception {
    var source = source("old"); var manifest = uploaded(source);
    files.publish(manifest,()->business(source));
    assertEquals(409,assertThrows(ApiException.class,()->files.retire(source.workspace,source.document)).status());
    assertEquals(409,assertThrows(ApiException.class,()->files.cleanDocument(source.workspace,source.document)).status());
    tombstone(source);
    assertEquals(409,assertThrows(ApiException.class,()->files.content(source.workspace,source.document)).status());
    files.retire(source.workspace,source.document);
    files.cleanDocument(source.workspace,source.document);
    files.cleanDocument(source.workspace,source.document);
    database.jdbc().update("DELETE FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid",source.workspace,source.document);
    var newer = source(source.workspace,source.document,"new"); var newManifest = uploaded(newer);
    files.publish(newManifest,()->business(newer));
    database.jdbc().update("UPDATE document_files SET cleanup_at=clock_timestamp() WHERE generation=?::uuid",manifest.generation());
    assertEquals(1,files.reap(4));
    assertArrayEquals(Files.readAllBytes(newer.content),files.content(source.workspace,source.document));
    assertEquals("READY",state(newManifest));
    assertEquals("GARBAGE",state(manifest));
  }

  @Test void cleanupFailureRetainsIntentAndIsNotImmediatelyRedriven() throws Exception {
    var source = source("cleanup-failure"); var manifest = uploaded(source);
    files.abandon(manifest);
    try (var unavailable = offline()) {
      var failing = new DocumentStorage(database.jdbc(),unavailable);
      assertEquals(503,assertThrows(ApiException.class,()->failing.reap(4)).status());
      assertEquals(0,failing.reap(4));
    }
    assertEquals("GARBAGE",state(manifest));
    database.jdbc().update("UPDATE document_files SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE generation=?::uuid",manifest.generation());
    assertEquals(1,files.reap(4));
    assertEquals(404,assertThrows(ApiException.class,()->objects.getContent(manifest.content())).status());
  }

  @Test void outageLeavesUnpublishedIntentAndNoLocalFallback() throws Exception {
    var source = source("offline"); var manifest = stage(source);
    try (var unavailable = offline()) {
      var failing = new DocumentStorage(database.jdbc(),unavailable);
      assertEquals(503,assertThrows(ApiException.class,()->failing.upload(manifest,source.pdf,source.content)).status());
    }
    assertEquals("STAGING",state(manifest));
    assertEquals(409,assertThrows(ApiException.class,()->files.publish(manifest,()->business(source))).status());
    files.abandon(manifest);
    assertEquals(1,files.reap(4));
  }

  @Test void networkCallsRejectEnclosingDatabaseTransactions() throws Exception {
    var source = source("transaction"); var manifest = uploaded(source);
    files.publish(manifest,()->business(source));
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(database.jdbc().getDataSource())));
    transaction.executeWithoutResult(status->{
      assertThrows(IllegalStateException.class,()->files.stage(source.workspace,source.document,source.hash,source.pdf,source.content));
      assertThrows(IllegalStateException.class,()->files.upload(manifest,source.pdf,source.content));
      assertThrows(IllegalStateException.class,()->files.content(source.workspace,source.document));
      assertThrows(IllegalStateException.class,()->files.pdf(source.workspace,source.document,root.resolve("copy")));
      assertThrows(IllegalStateException.class,()->files.cleanDocument(source.workspace,source.document));
      assertThrows(IllegalStateException.class,()->files.reap(4));
    });
  }

  @Test void workspaceUploadCapacityAndDatabaseConstraintsAreEnforced() throws Exception {
    var source = source("capacity");
    for (int i=0; i<4; i++) stage(source);
    assertEquals("UPLOAD_CAPACITY",assertThrows(ApiException.class,()->stage(source)).code());
    assertEquals(4,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files",Integer.class));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->database.jdbc().update("UPDATE document_files SET pdf_bytes=52428801"));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->database.jdbc().update("UPDATE document_files SET content_sha256='bad'"));
    assertEquals(422,assertThrows(ApiException.class,()->files.stage(UUID.randomUUID().toString(),source.document,"0".repeat(64),source.pdf,source.content)).status());
    assertEquals(4,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files",Integer.class));
    assertThrows(IllegalArgumentException.class,()->files.reap(33));
  }

  @Test void competingPublicationsLeaveOneReadyPointerAndMatchingDocument() throws Exception {
    var first = source("one"); var second = source(first.workspace,first.document,"two");
    var one = uploaded(first); var two = uploaded(second);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(2);
      var a = executor.submit(()->publishRacing(first,one,barrier));
      var b = executor.submit(()->publishRacing(second,two,barrier));
      int aResult = a.get(8,TimeUnit.SECONDS), bResult = b.get(8,TimeUnit.SECONDS);
      assertEquals(1,aResult+bResult);
      var winner = aResult == 1 ? first : second;
      assertEquals(winner.hash,files.ready(first.workspace,first.document).pdf().sha256());
      assertArrayEquals(Files.readAllBytes(winner.content),files.content(first.workspace,first.document));
      assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='READY'",Integer.class));
    }
  }

  int publishRacing(Source source, DocumentStorage.Manifest manifest, CyclicBarrier barrier) throws Exception {
    barrier.await(3,TimeUnit.SECONDS);
    try { return files.publish(manifest,()->business(source)) ? 1 : 0; }
    catch (org.springframework.dao.DataIntegrityViolationException e) { return 0; }
  }

  @Test void bucketChangesFailBeforeReadingOrDeletingWrongStorage() throws Exception {
    var source = source("bucket"); var manifest = uploaded(source);
    files.publish(manifest,()->business(source));
    var settings = database.objectSettings();
    try (var wrong = new S3ObjectStore(new S3Settings(settings.endpoint(),settings.region(),"pdf-workbench-other",settings.accessKey(),settings.secretKey()))) {
      var changed = new DocumentStorage(database.jdbc(),wrong);
      assertEquals("STORAGE_BUCKET_MISMATCH",assertThrows(ApiException.class,()->changed.content(source.workspace,source.document)).code());
      tombstone(source); files.retire(source.workspace,source.document);
      assertEquals("STORAGE_BUCKET_MISMATCH",assertThrows(ApiException.class,()->changed.cleanDocument(source.workspace,source.document)).code());
      assertEquals(0,changed.reap(4));
    }
    assertArrayEquals(Files.readAllBytes(source.content),objects.getContent(manifest.content()));
    files.cleanDocument(source.workspace,source.document);
  }

  S3ObjectStore offline() {
    var settings = database.objectSettings();
    return new S3ObjectStore(new S3Settings("http://127.0.0.1:9",settings.region(),settings.bucket(),settings.accessKey(),settings.secretKey()));
  }

  Path legacy(Source source, String text) throws Exception {
    var base = Files.createDirectories(root.resolve("legacy/users").resolve(source.workspace));
    Files.copy(source.pdf,base.resolve(source.document+".pdf"));
    var content = new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p1-0",1,0,text.length(),text,List.of())),List.of());
    new ObjectMapper().writeValue(base.resolve(source.document+".json").toFile(),content);
    business(source);
    return root.resolve("legacy");
  }

  @Test void legacyMigrationIsRepeatableAndPreservesSourceFiles() throws Exception {
    var source = source("legacy"); var base = legacy(source,"legacy text");
    var migration = new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),files);
    assertEquals(new MigrateDocumentFiles.Counts(1,0),migration.migrate(base));
    assertEquals(new MigrateDocumentFiles.Counts(0,1),migration.migrate(base));
    assertTrue(new String(files.content(source.workspace,source.document),java.nio.charset.StandardCharsets.UTF_8).contains("legacy text"));
    var original = base.resolve("users").resolve(source.workspace).resolve(source.document+".pdf");
    assertEquals(-1,Files.mismatch(source.pdf,original));
    tombstone(source);
    assertEquals(new MigrateDocumentFiles.Counts(0,0),migration.migrate(base));
    assertTrue(Files.isRegularFile(original));
  }

  @Test void invalidOrMissingLegacySourcesDoNotPublishOrModifyBusinessRecords() throws Exception {
    var source = source("legacy-invalid"); var base = legacy(source,"legacy");
    var pdf = base.resolve("users").resolve(source.workspace).resolve(source.document+".pdf");
    var content = pdf.resolveSibling(source.document+".json");
    var migration = new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),files);
    Files.writeString(content,"{}");
    assertThrows(java.io.IOException.class,()->migration.migrate(base));
    new ObjectMapper().writeValue(content.toFile(),new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p1-0",1,0,1,"x",List.of())),List.of()));
    Files.writeString(pdf,"%PDF-tampered");
    assertEquals("SOURCE_HASH_MISMATCH",assertThrows(ApiException.class,()->migration.migrate(base)).code());
    Files.delete(pdf);
    assertThrows(java.io.IOException.class,()->migration.migrate(base));
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files",Integer.class));
    assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM ai_documents",Integer.class));
  }

  @Test void migrationRejectsMalformedOffsetsCoordinatesAndChapterRanges() throws Exception {
    var source=source("invalid-content"); var base=legacy(source,"text");
    var path=base.resolve("users").resolve(source.workspace).resolve(source.document+".json");
    var valid=new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p1-0",1,0,1,"x",List.of());
    var malformed=List.of(
        new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(valid,valid),List.of()),
        new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p2-0",2,0,1,"x",List.of())),List.of()),
        new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p1-0",1,0,2,"x",List.of())),List.of()),
        new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chunk("p1-0",1,0,1,"x",List.of(new cn.edu.pdfworkbench.model.Rect(Float.NaN,0,1,1)))),List.of()),
        new cn.edu.pdfworkbench.ai.AiTypes.Content(List.of(valid),List.of(new cn.edu.pdfworkbench.ai.AiTypes.Chapter("c","title",1,2,1,1,"fixture"))));
    var migration=new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),files);
    for (var value : malformed) {
      new ObjectMapper().writeValue(path.toFile(),value);
      assertThrows(java.io.IOException.class,()->migration.migrate(base));
    }
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files",Integer.class));
  }

  @Test void failedLegacyUploadLeavesDurableGarbageForRecovery() throws Exception {
    var source = source("legacy-offline"); var base = legacy(source,"legacy");
    try (var client = offline()) {
      var migration = new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),new DocumentStorage(database.jdbc(),client));
      assertEquals(503,assertThrows(ApiException.class,()->migration.migrate(base)).status());
    }
    assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='GARBAGE'",Integer.class));
    assertEquals(0,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='READY'",Integer.class));
    assertEquals(1,files.reap(4));
    assertEquals(new MigrateDocumentFiles.Counts(1,0),new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),files).migrate(base));
    assertEquals(1,database.jdbc().queryForObject("SELECT COUNT(*) FROM document_files WHERE state='READY'",Integer.class));
  }

  @Test void migrationRetainsWorkspaceBoundariesForIdenticalDocumentIds() throws Exception {
    var first = source("tenant-a"); var second = source(UUID.randomUUID().toString(),first.document,"tenant-b");
    var base = legacy(first,"tenant-a"); legacy(second,"tenant-b");
    assertEquals(new MigrateDocumentFiles.Counts(2,0),new MigrateDocumentFiles(database.jdbc(),new ObjectMapper(),files).migrate(base));
    assertTrue(new String(files.content(first.workspace,first.document),java.nio.charset.StandardCharsets.UTF_8).contains("tenant-a"));
    assertFalse(new String(files.content(first.workspace,first.document),java.nio.charset.StandardCharsets.UTF_8).contains("tenant-b"));
    assertTrue(new String(files.content(second.workspace,second.document),java.nio.charset.StandardCharsets.UTF_8).contains("tenant-b"));
  }

  @Test void cleanupHandlerKeepsBusinessTombstoneOnS3FailureThenDeletesAfterRecovery() throws Exception {
    var source=source("task-cleanup"); var manifest=uploaded(source);
    files.publish(manifest,()->business(source)); tombstone(source);
    var settings=new cn.edu.pdfworkbench.ai.AiSettings(root.toString(),"","http://127.0.0.1:9","http://127.0.0.1:9","chat","embed","rank");
    var repository=new cn.edu.pdfworkbench.ai.AiRepository(settings,new ObjectMapper(),database.jdbc(),database.files());
    var documents=org.mockito.Mockito.mock(cn.edu.pdfworkbench.ai.AiDocuments.class);
    var tasks=new cn.edu.pdfworkbench.tasks.TaskStore(database.jdbc(),new cn.edu.pdfworkbench.tasks.TaskSettings("pdf-workbench.unit","worker",30,128,3,900));
    var task=new cn.edu.pdfworkbench.tasks.TaskStore.Task(UUID.randomUUID().toString(),source.workspace,cn.edu.pdfworkbench.tasks.TaskStore.Kind.CLEAN,
        source.document,source.document,null,"RUNNING",1,1,1,"");
    try (var scope=repository.scope(source.workspace,()->true); var unavailable=offline()) {
      var failing=new cn.edu.pdfworkbench.ai.AiTaskHandler(repository,documents,null,null,settings,tasks,new DocumentStorage(database.jdbc(),unavailable));
      assertEquals(503,assertThrows(ApiException.class,()->failing.execute(task)).status());
      assertEquals("DELETING",repository.document(source.document).status());
      assertEquals("GARBAGE",state(manifest));
      var restored=new cn.edu.pdfworkbench.ai.AiTaskHandler(repository,documents,null,null,settings,tasks,files);
      restored.execute(task).run();
      assertEquals(404,assertThrows(ApiException.class,()->repository.document(source.document)).status());
      assertEquals(404,assertThrows(ApiException.class,()->objects.getContent(manifest.content())).status());
    }
  }
}
