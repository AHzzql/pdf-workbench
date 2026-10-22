package cn.edu.pdfworkbench.tasks;

import cn.edu.pdfworkbench.util.Identifiers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskDeadLetters {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TaskSettings settings;
  public TaskDeadLetters(JdbcTemplate jdbc,ObjectMapper json,TaskSettings settings) {
    this.jdbc=jdbc;this.json=json;this.settings=settings;
  }
  public void archive(byte[] body) {
    if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Dead letter acknowledgement requires a committed archive");
    String fingerprint;
    try {fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    String task=null;Long version=null;
    try {
      if(body.length>256)throw new IllegalArgumentException();
      var value=json.readTree(body);
      if(value==null || !value.isObject() || value.size()!=2 || !value.path("taskId").isTextual()
          || !value.path("version").isIntegralNumber() || !value.path("version").canConvertToLong()
          || value.path("version").longValue()<1)throw new IllegalArgumentException();
      Identifiers.requireUuid(value.path("taskId").textValue());
      task=value.path("taskId").textValue();version=value.path("version").longValue();
    } catch(java.io.IOException | RuntimeException e) { /* Invalid payloads retain only their fingerprint and size. */ }
    jdbc.update("INSERT INTO task_dead_letters(namespace,fingerprint,body_bytes,task_id,dispatch_version) VALUES(?,?,?,?::uuid,?)"
        + " ON CONFLICT(namespace,fingerprint) DO UPDATE SET last_seen=clock_timestamp()",
        settings.namespace(),fingerprint,body.length,task,version);
  }
}
