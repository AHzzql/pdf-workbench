package cn.edu.pdfworkbench.auth;

public final class AccountTypes {
  private AccountTypes() {}

  public record User(String id, String email, String displayName) {}

  public record Identity(String id, long version) implements java.io.Serializable {}

  public record Session(boolean authenticated, User user, String workspaceId) {}

  public record Register(String email, String displayName, String password) {}

  public record Login(String email, String password) {}

  public record Profile(String displayName) {}

  public record Password(String currentPassword, String newPassword) {}

  public record Row(String id, String email, String displayName, String hash, long version) {
    public User user() {
      return new User(id, email, displayName);
    }

    @Override
    public String toString() {
      return "AccountRow[id=" + id + "]";
    }
  }
}
