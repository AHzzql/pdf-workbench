package cn.edu.pdfworkbench.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ChatWorkspace {
  private ChatWorkspace() {}

  static String guest(String sessionId) {
    return UUID.nameUUIDFromBytes(("pdf-workbench/guest/" + sessionId).getBytes(StandardCharsets.UTF_8)).toString();
  }

  static String owner(HttpServletRequest request, AccountTypes.Identity identity) {
    return identity == null ? guest(request.getSession(true).getId()) : identity.id();
  }
}
