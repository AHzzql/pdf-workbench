package cn.edu.pdfworkbench.model;

import java.nio.file.Path;
import java.util.Arrays;

/** 签名参数拥有一份证书密码副本，使用结束后关闭以清理该副本。 */
public final class SigningOptions implements AutoCloseable {
  private final Path certificate;
  private final char[] certificatePassword;
  private final String alias;
  private final String reason;
  private final String location;
  private boolean closed;

  public SigningOptions(
      Path certificate, char[] certificatePassword, String alias, String reason, String location) {
    if (certificate == null || certificatePassword == null)
      throw new IllegalArgumentException("证书与密码不能为空");
    this.certificate = certificate;
    this.certificatePassword = certificatePassword.clone();
    this.alias = alias;
    this.reason = reason;
    this.location = location;
  }

  public Path certificate() {
    return certificate;
  }

  public char[] certificatePassword() {
    if (closed) throw new IllegalStateException("签名参数已关闭");
    return certificatePassword.clone();
  }

  public String alias() {
    return alias;
  }

  public String reason() {
    return reason;
  }

  public String location() {
    return location;
  }

  @Override
  public void close() {
    Arrays.fill(certificatePassword, '\0');
    closed = true;
  }

  @Override
  public String toString() {
    return "SigningOptions[certificatePassword=REDACTED]";
  }
}
