package cn.edu.pdfworkbench.config;

import cn.edu.pdfworkbench.exception.ApiExceptionHandler.ErrorBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public class SessionStorageFailureFilter extends OncePerRequestFilter {
  private final ObjectMapper json;

  public SessionStorageFailureFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (DataAccessException e) {
      if (response.isCommitted()) throw e;
      response.reset();
      response.setStatus(503);
      response.setContentType("application/json;charset=UTF-8");
      response.setHeader("Cache-Control", "no-store");
      json.writeValue(response.getWriter(), new ErrorBody(503, "SESSION_STORAGE_UNAVAILABLE", "会话服务暂不可用，请稍后重试"));
    }
  }
}
