package cn.edu.pdfworkbench.auth;

import static cn.edu.pdfworkbench.auth.AccountTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
  private final Path root;
  private JdbcTemplate database;

  public AccountRepository(
      @Value("${account.data-directory:${user.home}/.pdf-workbench/accounts}") String directory) {
    root = Path.of(directory).toAbsolutePath().normalize();
  }

  private synchronized JdbcTemplate db() {
    if (database != null) return database;
    try {
      Files.createDirectories(root);
      var db =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  "jdbc:h2:file:"
                      + root.resolve("account").toString().replace('\\', '/')
                      + ";DB_CLOSE_ON_EXIT=FALSE",
                  "sa",
                  ""));
      db.execute(
          "CREATE TABLE IF NOT EXISTS accounts(id VARCHAR(36) PRIMARY KEY,email VARCHAR(254) NOT"
              + " NULL UNIQUE,display_name VARCHAR(96) NOT NULL,password_hash VARCHAR(512) NOT"
              + " NULL,credential_version BIGINT NOT NULL)");
      database = db;
      return db;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public Optional<Row> findEmail(String email) {
    return query("email", email);
  }

  public Optional<Row> findId(String id) {
    return query("id", id);
  }

  private Optional<Row> query(String column, String value) {
    try {
      return db()
          .query(
              "SELECT * FROM accounts WHERE " + column + "=?",
              (r, n) ->
                  new Row(
                      r.getString("id"),
                      r.getString("email"),
                      r.getString("display_name"),
                      r.getString("password_hash"),
                      r.getLong("credential_version")),
              value)
          .stream()
          .findFirst();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  public Row insert(String email, String name, String hash) {
    var row = new Row(UUID.randomUUID().toString(), email, name, hash, 1);
    try {
      db().update("INSERT INTO accounts VALUES(?,?,?,?,?)", row.id(), email, name, hash, 1);
      return row;
    } catch (DuplicateKeyException e) {
      throw new ApiException(409, "ACCOUNT_EMAIL_UNAVAILABLE", "该邮箱暂不可用于注册");
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  public User rename(String id, String name) {
    try {
      db().update("UPDATE accounts SET display_name=? WHERE id=?", name, id);
      return findId(id).orElseThrow().user();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  public boolean password(Row row, String hash) {
    try {
      return db().update(
                  "UPDATE accounts SET password_hash=?,credential_version=credential_version+1"
                      + " WHERE id=? AND credential_version=?",
                  hash,
                  row.id(),
                  row.version())
          == 1;
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "ACCOUNT_STORAGE_UNAVAILABLE", "账户服务暂不可用，请稍后重试");
  }
}
