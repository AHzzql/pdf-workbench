package cn.edu.pdfworkbench.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestPolicyFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith("/api/v1/pdf")) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      if (request.getRequestURI().startsWith("/api/v1/pdf/ai/")) {
        String origin = request.getHeader("Origin");
        boolean validOrigin =
            origin == null || origin.matches("http://(localhost|127\\.0\\.0\\.1):(5173|8080)");
        if (!validOrigin || !SetHolder.HOSTS.contains(request.getServerName())) {
          response.setStatus(403);
          response.setContentType("application/json;charset=UTF-8");
          response
              .getWriter()
              .write("{\"status\":403,\"code\":\"ORIGIN_DENIED\",\"message\":\"不允许此访问来源\"}");
          return;
        }
      }
      if (request.getQueryString() != null) {
        response.setStatus(400);
        response.setContentType("application/json;charset=UTF-8");
        response
            .getWriter()
            .write(
                "{\"status\":400,\"code\":\"BODY_PARAMETERS_REQUIRED\",\"message\":\"参数必须放入请求正文\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static final class SetHolder {
    private static final java.util.Set<String> HOSTS =
        java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1");
  }
}
