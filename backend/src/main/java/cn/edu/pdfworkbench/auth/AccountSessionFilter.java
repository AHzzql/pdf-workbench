package cn.edu.pdfworkbench.auth;

import cn.edu.pdfworkbench.ai.AiRepository;
import cn.edu.pdfworkbench.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountSessionFilter extends OncePerRequestFilter {
  private final AccountService service;
  private final AiRepository repository;
  private final ObjectMapper json;

  public AccountSessionFilter(AccountService service, AiRepository repository, ObjectMapper json) {
    this.service = service;
    this.repository = repository;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    boolean ai = path.startsWith("/api/v1/pdf/ai/");
    try {
      var identity = AccountController.identity();
      boolean check =
          ai
              || path.endsWith("/session")
              || path.endsWith("/profile")
              || path.endsWith("/password");
      if (identity != null && check && service.current(identity) == null) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        identity = null;
        if (ai) throw new ApiException(401, "ACCOUNT_LOGIN_REQUIRED", "账户会话已失效，请重新登录或继续游客使用");
      }
      String owner = identity == null ? "guest" : identity.id();
      String expected = request.getHeader("X-Chat-Space");
      if (ai && expected != null && !expected.equals(owner))
        throw new ApiException(409, "CHAT_SPACE_CHANGED", "聊天空间已切换，请重新打开智能助手");
      if (!ai) {
        chain.doFilter(request, response);
        return;
      }
      final var account = identity;
      final var session = request.getSession(false);
      BooleanSupplier access =
          account == null
              ? () -> true
              : () -> {
                try {
                  if (session == null) return false;
                  var context = session.getAttribute("SPRING_SECURITY_CONTEXT");
                  return context
                          instanceof org.springframework.security.core.context.SecurityContext c
                      && c.getAuthentication() != null
                      && account.equals(c.getAuthentication().getPrincipal())
                      && service.current(account) != null;
                } catch (Exception e) {
                  return false;
                }
              };
      try (var scope = repository.scope(owner, access)) {
        chain.doFilter(request, response);
      }
    } catch (ApiException e) {
      response.setStatus(e.status());
      response.setContentType("application/json;charset=UTF-8");
      json.writeValue(
          response.getWriter(),
          new ApiExceptionHandler.ErrorBody(e.status(), e.code(), e.getMessage()));
    }
  }
}
