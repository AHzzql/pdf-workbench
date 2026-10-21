package cn.edu.pdfworkbench.auth;

import static cn.edu.pdfworkbench.auth.AccountTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
  private final JdbcTemplate database;

  public AccountRepository(JdbcTemplate database) {
    this.database = database;
  }

  public Optional<Row> findEmail(String email) {
    return query("email", email);
  }

  public Optional<Row> findId(String id) {
    return query("id", id);
  }

  private Optional<Row> query(String column, String value) {
    try {
      return database
          .query(
              "SELECT id,email,display_name,password_hash,credential_version FROM accounts WHERE " + column + (column.equals("id") ? "=?::uuid" : "=?"),
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
      database.update("INSERT INTO accounts(id,email,display_name,password_hash,credential_version) VALUES(?::uuid,?,?,?,?)", row.id(), email, name, hash, 1);
      return row;
    } catch (DuplicateKeyException e) {
      throw new ApiException(409, "ACCOUNT_EMAIL_UNAVAILABLE", "该邮箱暂不可用于注册");
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  public User rename(String id, String name) {
    try {
      database.update("UPDATE accounts SET display_name=? WHERE id=?::uuid", name, id);
      return findId(id).orElseThrow().user();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  public boolean password(Row row, String hash) {
    try {
      return database.update(
                  "UPDATE accounts SET password_hash=?,credential_version=credential_version+1"
                      + " WHERE id=?::uuid AND credential_version=?",
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
