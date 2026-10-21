package cn.edu.pdfworkbench.auth;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SessionAccess {
  private final SessionRepository<? extends Session> sessions;
  private final AccountService accounts;

  public SessionAccess(SessionRepository<? extends Session> sessions, AccountService accounts) {
    this.sessions = sessions;
    this.accounts = accounts;
  }

  public boolean active(String sessionId, AccountTypes.Identity expected, String owner) {
    try {
      var session = sessions.findById(sessionId);
      if (session == null || session.isExpired()) return false;
      var context = session.getAttribute("SPRING_SECURITY_CONTEXT");
      var principal = context instanceof SecurityContext c && c.getAuthentication() != null
          ? c.getAuthentication().getPrincipal() : null;
      if (expected == null)
        return !(principal instanceof AccountTypes.Identity) && ChatWorkspace.guest(sessionId).equals(owner);
      return expected.equals(principal) && accounts.current(expected) != null;
    } catch (Exception e) {
      return false;
    }
  }
}
