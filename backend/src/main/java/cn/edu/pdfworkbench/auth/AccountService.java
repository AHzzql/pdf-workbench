package cn.edu.pdfworkbench.auth;

import static cn.edu.pdfworkbench.auth.AccountTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import java.util.*;
import org.springframework.security.crypto.password.*;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
  private final AccountRepository repository;
  private final AccountAttemptLimiter limiter;
  private final PasswordEncoder encoder;
  private final String dummy;

  public AccountService(AccountRepository repository, AccountAttemptLimiter limiter) {
    this.repository = repository;
    this.limiter = limiter;
    encoder =
        new DelegatingPasswordEncoder(
            "pbkdf2-sha256-600k",
            Map.of(
                "pbkdf2-sha256-600k",
                new Pbkdf2PasswordEncoder(
                    "",
                    16,
                    600000,
                    Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256)));
    dummy = encoder.encode(UUID.randomUUID().toString());
  }

  private String email(String value) {
    String email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw invalid("请输入有效邮箱");
    return email;
  }

  private String name(String value) {
    String name = value == null ? "" : value.strip();
    int count = name.codePointCount(0, name.length());
    if (count < 1 || count > 24 || name.codePoints().anyMatch(Character::isISOControl))
      throw invalid("昵称须为 1–24 个字符");
    return name;
  }

  private void password(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) < 12
        || value.codePointCount(0, value.length()) > 128) throw invalid("密码须为 12–128 个字符");
  }

  public User register(Register value, String ip) {
    if (value == null) throw invalid("请输入注册资料");
    limiter.check("register:" + ip, 10, true);
    String email = email(value.email()), name = name(value.displayName());
    password(value.password());
    return repository.insert(email, name, encoder.encode(value.password())).user();
  }

  public Row login(Login value, String ip) {
    if (value == null) throw invalid("请输入邮箱与密码");
    String email = email(value.email()), key = "login:" + ip + ":" + email;
    limiter.check(key, 5, false);
    var row = repository.findEmail(email);
    String password = value.password();
    boolean valid =
        password != null
            && password.length() <= 512
            && encoder.matches(password, row.map(Row::hash).orElse(dummy));
    if (!valid || row.isEmpty()) {
      limiter.check(key, 5, true);
      throw new ApiException(401, "ACCOUNT_BAD_CREDENTIALS", "邮箱或密码不正确");
    }
    limiter.clear(key);
    return row.get();
  }

  public Row current(Identity identity) {
    var row = repository.findId(identity.id());
    return row.filter(r -> r.version() == identity.version()).orElse(null);
  }

  public User profile(Identity identity, Profile value) {
    if (value == null) throw invalid("请输入昵称");
    return repository.rename(identity.id(), name(value.displayName()));
  }

  public void changePassword(Identity identity, Password value, String ip) {
    if (value == null) throw invalid("请输入当前密码和新密码");
    String key = "password:" + ip + ":" + identity.id();
    limiter.check(key, 5, false);
    password(value.newPassword());
    var row = current(identity);
    if (row == null) throw new ApiException(401, "ACCOUNT_LOGIN_REQUIRED", "请重新登录账户");
    if (value.currentPassword() == null
        || value.currentPassword().length() > 512
        || !encoder.matches(value.currentPassword(), row.hash())
        || value.currentPassword().equals(value.newPassword())) {
      limiter.check(key, 5, true);
      throw new ApiException(400, "ACCOUNT_PASSWORD_REJECTED", "当前密码不正确，或新密码与当前密码相同");
    }
    if (!repository.password(row, encoder.encode(value.newPassword())))
      throw new ApiException(409, "ACCOUNT_CREDENTIALS_CHANGED", "密码已变更，请重新登录");
    limiter.clear(key);
  }

  private ApiException invalid(String message) {
    return new ApiException(400, "ACCOUNT_INVALID_INPUT", message);
  }
}
