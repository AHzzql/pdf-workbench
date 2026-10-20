package cn.edu.pdfworkbench.auth;

import static cn.edu.pdfworkbench.auth.AccountTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
  private final AccountService service;
  private final HttpSessionSecurityContextRepository contexts =
      new HttpSessionSecurityContextRepository();

  public AccountController(AccountService service) {
    this.service = service;
  }

  public static Identity identity() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    return a != null && a.getPrincipal() instanceof Identity i ? i : null;
  }

  private Identity required() {
    var i = identity();
    if (i == null) throw new ApiException(401, "ACCOUNT_LOGIN_REQUIRED", "请先登录账户");
    return i;
  }

  @GetMapping("/session")
  public Session session(HttpServletRequest request) {
    var i = identity();
    var row = i == null ? null : service.current(i);
    return new Session(row != null, row == null ? null : row.user(), ChatWorkspace.owner(request, i));
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(HttpServletRequest request) {
    var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public User register(@RequestBody Register value, HttpServletRequest request) {
    return service.register(value, request.getRemoteAddr());
  }

  @PostMapping("/login")
  public Session login(
      @RequestBody Login value, HttpServletRequest request, HttpServletResponse response) {
    var row = service.login(value, request.getRemoteAddr());
    request.getSession(true);
    request.changeSessionId();
    request.getSession(false).removeAttribute(ChatWorkspace.GUEST_ATTRIBUTE);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new Identity(row.id(), row.version()), null, List.of()));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
    return new Session(true, row.user(), row.id());
  }

  @GetMapping("/profile")
  public User profile() {
    return service.current(required()).user();
  }

  @PatchMapping("/profile")
  public User rename(@RequestBody Profile value) {
    return service.profile(required(), value);
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(@RequestBody Password value, HttpServletRequest request) {
    service.changePassword(required(), value, request.getRemoteAddr());
    logout(request);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
  }
}
