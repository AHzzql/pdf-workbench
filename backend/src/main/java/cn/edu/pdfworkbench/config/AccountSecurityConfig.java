package cn.edu.pdfworkbench.config;

import cn.edu.pdfworkbench.ai.AiRepository;
import cn.edu.pdfworkbench.auth.*;
import cn.edu.pdfworkbench.exception.ApiExceptionHandler.ErrorBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class AccountSecurityConfig {
  @Bean
  AuthenticationManager accountAuthenticationManager() {
    return authentication -> {
      throw new org.springframework.security.authentication.BadCredentialsException("请使用账户登录接口");
    };
  }

  @Bean
  SecurityFilterChain accounts(
      HttpSecurity http, AccountService service, AiRepository repository, ObjectMapper json, SessionAccess sessions)
      throws Exception {
    http.securityMatcher("/api/v1/account/**", "/api/v1/pdf/ai/**")
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/v1/account/profile", "/api/v1/account/password")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (request, response, e) -> {
                          response.setStatus(401);
                          response.setContentType("application/json;charset=UTF-8");
                          json.writeValue(
                              response.getWriter(),
                              new ErrorBody(401, "ACCOUNT_LOGIN_REQUIRED", "请先登录账户"));
                        })
                    .accessDeniedHandler(
                        (request, response, e) -> {
                          response.setStatus(403);
                          response.setContentType("application/json;charset=UTF-8");
                          json.writeValue(
                              response.getWriter(),
                              new ErrorBody(403, "ACCOUNT_CSRF_INVALID", "会话校验已过期，请重新提交"));
                        }))
        .addFilterAfter(
            new AccountSessionFilter(service, repository, json, sessions), SecurityContextHolderFilter.class);
    return http.build();
  }
}
