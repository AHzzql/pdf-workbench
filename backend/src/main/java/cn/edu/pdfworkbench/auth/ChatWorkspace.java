package cn.edu.pdfworkbench.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class ChatWorkspace {
  static final String GUEST_ATTRIBUTE = ChatWorkspace.class.getName() + ".guest";

  private ChatWorkspace() {}

  static String owner(HttpServletRequest request, AccountTypes.Identity identity) {
    if (identity != null) return identity.id();
    var session = request.getSession(true);
    synchronized (session) {
      var owner = (String) session.getAttribute(GUEST_ATTRIBUTE);
      if (owner == null) {
        owner = UUID.randomUUID().toString();
        session.setAttribute(GUEST_ATTRIBUTE, owner);
      }
      return owner;
    }
  }
}
