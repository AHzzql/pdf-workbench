package cn.edu.pdfworkbench.storage;

import cn.edu.pdfworkbench.ai.AiRepository;
import cn.edu.pdfworkbench.exception.ApiException;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** 文件清单与跨资源发布；网络操作在数据库事务之外执行。 */
public final class DocumentStorage {
  public record Manifest(String workspace, String document, String generation, String bucket, String state,
      S3ObjectStore.ObjectRef pdf, S3ObjectStore.ObjectRef content, boolean verified) {}
  private record Cleanup(Manifest manifest, String token) {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final S3ObjectStore objects;
  private final String bucket;

  public DocumentStorage(JdbcTemplate jdbc, S3ObjectStore objects) {
    this.jdbc = jdbc; this.objects = objects; this.bucket = objects.bucket();
    transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  private Manifest row(ResultSet r, int n) throws SQLException {
    String workspace = r.getString("workspace_id"), document = r.getString("document_id"), generation = r.getString("generation");
    return new Manifest(workspace, document, generation, r.getString("bucket"), r.getString("state"),
        new S3ObjectStore.ObjectRef(new S3ObjectStore.Key(workspace,document,generation,S3ObjectStore.Kind.PDF), r.getLong("pdf_bytes"), r.getString("pdf_sha256")),
        new S3ObjectStore.ObjectRef(new S3ObjectStore.Key(workspace,document,generation,S3ObjectStore.Kind.CONTENT), r.getLong("content_bytes"), r.getString("content_sha256")),
        r.getTimestamp("verified_at") != null);
  }

  public Manifest stage(String workspace, String document, String expectedPdfHash, Path pdf, Path content) throws IOException {
    AiRepository.validateId(workspace); AiRepository.validateId(document);
    outsideTransaction();
    String generation = UUID.randomUUID().toString();
    var source = objects.inspect(new S3ObjectStore.Key(workspace,document,generation,S3ObjectStore.Kind.PDF), pdf);
    var derived = objects.inspect(new S3ObjectStore.Key(workspace,document,generation,S3ObjectStore.Kind.CONTENT), content);
    if (!source.sha256().equals(expectedPdfHash)) throw new ApiException(422,"SOURCE_HASH_MISMATCH","原始文件与文档校验值不一致");
    return transactions.execute(status -> {
      jdbc.execute("SET LOCAL lock_timeout='3s'");
      jdbc.queryForObject("SELECT pg_advisory_xact_lock(736281941)", Object.class);
      readableDocumentIfPresent(workspace,document,true);
      if (jdbc.queryForObject("SELECT COUNT(*) FROM document_files WHERE state='STAGING'", Integer.class) >= 64
          || jdbc.queryForObject("SELECT COUNT(*) FROM document_files WHERE state='STAGING' AND workspace_id=?::uuid", Integer.class,workspace) >= 4)
        throw new ApiException(503,"UPLOAD_CAPACITY","文件上传繁忙，请稍后重试");
      jdbc.update("INSERT INTO document_files(workspace_id,document_id,generation,bucket,state,pdf_bytes,pdf_sha256,content_bytes,content_sha256,lease_until)"
          + " VALUES(?::uuid,?::uuid,?::uuid,?,'STAGING',?,?,?,?,clock_timestamp()+interval '10 minutes')",
          workspace,document,generation,bucket,source.bytes(),source.sha256(),derived.bytes(),derived.sha256());
      return get(workspace,document,generation,false);
    });
  }

  public void upload(Manifest value, Path pdf, Path content) throws IOException {
    outsideTransaction();
    var current = get(value.workspace,value.document,value.generation,false);
    requireBucket(current);
    renew(current);
    putOrVerify(current.pdf,pdf);
    renew(current);
    putOrVerify(current.content,content);
    renew(current);
    verify(current.pdf);
    renew(current);
    objects.getContent(current.content);
    if (jdbc.update("UPDATE document_files SET verified_at=clock_timestamp() WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid"
        + " AND state='STAGING' AND lease_until>clock_timestamp()", current.workspace,current.document,current.generation) != 1) throw expired();
  }

  private void putOrVerify(S3ObjectStore.ObjectRef ref, Path path) throws IOException {
    try { objects.put(ref,path); }
    catch (ApiException e) { if (!e.code().equals("OBJECT_EXISTS")) throw e; verify(ref); }
  }

  private void verify(S3ObjectStore.ObjectRef ref) throws IOException {
    if (ref.key().kind() == S3ObjectStore.Kind.CONTENT) { objects.getContent(ref); return; }
    Path directory = Files.createTempDirectory("pdf-object-verify-");
    Path copy = directory.resolve("source.pdf");
    try { objects.get(ref,copy); }
    finally { Files.deleteIfExists(copy); Files.deleteIfExists(directory); }
  }

  private void renew(Manifest value) {
    if (jdbc.update("UPDATE document_files SET lease_until=clock_timestamp()+interval '10 minutes' WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid"
        + " AND state='STAGING' AND lease_until>clock_timestamp()", value.workspace,value.document,value.generation) != 1) throw expired();
  }

  /** 发布回调只包含同数据源的业务写入；重复确认不会再次执行回调。 */
  public boolean publish(Manifest value, Runnable businessWrite) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      jdbc.execute("SET LOCAL lock_timeout='3s'");
      boolean present = readableDocumentIfPresent(value.workspace,value.document,true);
      var current = get(value.workspace,value.document,value.generation,true);
      requireBucket(current);
      if (current.state.equals("READY")) { if (!present) throw expired(); checkDocumentHash(current); return false; }
      if (!current.state.equals("STAGING") || !current.verified || !validLease(current)) throw expired();
      businessWrite.run();
      if (!readableDocumentIfPresent(current.workspace,current.document,false))
        throw new ApiException(409,"DOCUMENT_NOT_PUBLISHED","业务文档尚未发布");
      checkDocumentHash(current);
      if (jdbc.update("UPDATE document_files SET state='READY',lease_until=NULL WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid"
          + " AND state='STAGING' AND lease_until>clock_timestamp()",current.workspace,current.document,current.generation) != 1) throw expired();
      return true;
    }));
  }

  private boolean validLease(Manifest value) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM document_files WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid",
        Boolean.class,value.workspace,value.document,value.generation));
  }

  public Manifest ready(String workspace, String document) {
    AiRepository.validateId(workspace); AiRepository.validateId(document);
    if (!readableDocumentIfPresent(workspace,document,false)) throw new ApiException(404,"NOT_FOUND","记录不存在或已删除");
    var rows = jdbc.query("SELECT * FROM document_files WHERE workspace_id=?::uuid AND document_id=?::uuid AND state='READY'",this::row,workspace,document);
    if (rows.isEmpty()) throw new ApiException(503,"FILES_NOT_PUBLISHED","文档文件尚未完成存储发布");
    var value = rows.getFirst(); requireBucket(value); checkDocumentHash(value); return value;
  }

  public void pdf(String workspace, String document, Path destination) throws IOException {
    outsideTransaction();
    var value = ready(workspace,document);
    objects.get(value.pdf,destination);
    try { ensureCurrent(value); }
    catch (RuntimeException e) { Files.deleteIfExists(destination); throw e; }
  }

  public byte[] content(String workspace, String document) throws IOException {
    outsideTransaction();
    var value = ready(workspace,document);
    var content = objects.getContent(value.content);
    ensureCurrent(value);
    return content;
  }

  public void abandon(Manifest value) {
    jdbc.update("UPDATE document_files SET state='GARBAGE',lease_until=NULL,cleanup_at=clock_timestamp() WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid AND state='STAGING'",
        value.workspace,value.document,value.generation);
  }

  public void retire(String workspace, String document) {
    AiRepository.validateId(workspace); AiRepository.validateId(document);
    transactions.executeWithoutResult(status -> {
      var states = jdbc.query("SELECT payload->>'status' FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid FOR UPDATE",(r,n)->r.getString(1),workspace,document);
      if (!states.isEmpty() && !states.getFirst().equals("DELETING")) throw new ApiException(409,"DOCUMENT_NOT_DELETING","文档尚未进入清理状态");
      jdbc.update("UPDATE document_files SET state='GARBAGE',lease_until=NULL,cleanup_at=clock_timestamp() WHERE workspace_id=?::uuid AND document_id=?::uuid AND state IN ('STAGING','READY')",workspace,document);
    });
  }

  public void cleanDocument(String workspace, String document) {
    outsideTransaction();
    AiRepository.validateId(workspace); AiRepository.validateId(document);
    var rows = jdbc.query("SELECT * FROM document_files WHERE workspace_id=?::uuid AND document_id=?::uuid",this::row,workspace,document);
    if (rows.stream().anyMatch(value -> !value.state.equals("GARBAGE")))
      throw new ApiException(409,"FILES_NOT_RETIRED","文档文件尚未进入清理状态");
    for (var value : rows) {
      requireBucket(value);
      removeObjects(value);
      jdbc.update("UPDATE document_files SET cleanup_at=clock_timestamp()+interval '1 day' WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid AND state='GARBAGE'",
          workspace,document,value.generation);
    }
  }

  /** GARBAGE 不再发布；保留清理标识并周期复查，以覆盖旧上传者的迟到写入。 */
  public int reap(int limit) {
    outsideTransaction();
    if (limit < 1 || limit > 32) throw new IllegalArgumentException("Invalid cleanup batch size");
    jdbc.update("UPDATE document_files SET state='GARBAGE',lease_until=NULL,cleanup_at=clock_timestamp() WHERE (workspace_id,document_id,generation) IN"
        + " (SELECT workspace_id,document_id,generation FROM document_files WHERE bucket=? AND state='STAGING' AND lease_until<=clock_timestamp()"
        + " ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED)",bucket);
    int completed = 0;
    for (int i=0; i<limit; i++) {
      var claim = claimCleanup();
      if (claim == null) break;
      var value = claim.manifest;
      boolean removed = false;
      try { removeObjects(value); completed++; removed = true; }
      finally {
        // 失败保留短租约，阻止立即重复请求；成功的每日复查保留精确对象标识。
        if (removed) jdbc.update("UPDATE document_files SET cleanup_token=NULL,cleanup_lease_until=NULL,cleanup_at=clock_timestamp()+interval '1 day'"
            + " WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid AND cleanup_token=?::uuid",
            value.workspace,value.document,value.generation,claim.token);
      }
    }
    return completed;
  }

  private Cleanup claimCleanup() {
    return transactions.execute(status -> {
      var rows = jdbc.query("SELECT * FROM document_files WHERE bucket=? AND state='GARBAGE' AND cleanup_at<=clock_timestamp()"
          + " AND (cleanup_lease_until IS NULL OR cleanup_lease_until<=clock_timestamp()) ORDER BY cleanup_at,generation LIMIT 1 FOR UPDATE SKIP LOCKED",this::row,bucket);
      if (rows.isEmpty()) return null;
      var value = rows.getFirst(); String token = UUID.randomUUID().toString();
      jdbc.update("UPDATE document_files SET cleanup_token=?::uuid,cleanup_lease_until=clock_timestamp()+interval '5 minutes' WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid",
          token,value.workspace,value.document,value.generation);
      return new Cleanup(value,token);
    });
  }

  private void removeObjects(Manifest value) {
    requireBucket(value);
    objects.delete(value.pdf.key()); objects.delete(value.content.key());
  }

  private Manifest get(String workspace, String document, String generation, boolean lock) {
    AiRepository.validateId(workspace); AiRepository.validateId(document); AiRepository.validateId(generation);
    var rows = jdbc.query("SELECT * FROM document_files WHERE workspace_id=?::uuid AND document_id=?::uuid AND generation=?::uuid" + (lock ? " FOR UPDATE" : ""),this::row,workspace,document,generation);
    if (rows.isEmpty()) throw expired();
    return rows.getFirst();
  }

  private boolean readableDocumentIfPresent(String workspace, String document, boolean lock) {
    var states = jdbc.query("SELECT payload->>'status' FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid" + (lock ? " FOR UPDATE" : ""),(r,n)->r.getString(1),workspace,document);
    if (!states.isEmpty() && states.getFirst().equals("DELETING")) throw new ApiException(409,"DOCUMENT_DELETING","文档正在清理");
    return !states.isEmpty();
  }

  private void checkDocumentHash(Manifest value) {
    if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_documents WHERE workspace_id=?::uuid AND id=?::uuid AND payload->>'hash'=?)",
        Boolean.class,value.workspace,value.document,value.pdf.sha256())))
      throw new ApiException(503,"DOCUMENT_FILE_MISMATCH","业务文档与文件清单不一致");
  }

  private void ensureCurrent(Manifest value) {
    if (!ready(value.workspace,value.document).generation.equals(value.generation))
      throw new ApiException(409,"DOCUMENT_CHANGED","文档文件已变更，请重新读取");
  }

  private void requireBucket(Manifest value) {
    if (!bucket.equals(value.bucket)) throw new ApiException(503,"STORAGE_BUCKET_MISMATCH","文件存储配置与清单不一致");
  }

  private static void outsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Object transfer cannot run inside a database transaction");
  }
  private static ApiException expired() { return new ApiException(409,"UPLOAD_LEASE_LOST","文件上传已失效，请重新导入"); }
}
