package cn.edu.pdfworkbench.config;

import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SessionBoundaryConfig {
  @Bean
  static BeanPostProcessor sessionRouteRegistration() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String name) {
        if (name.equals("sessionRepositoryFilterRegistration")
            && bean instanceof DelegatingFilterProxyRegistrationBean registration) {
          // Spring MVC also inspects sessions on stateless requests; keep Redis outside that path.
          registration.setUrlPatterns(List.of("/api/v1/account/*", "/api/v1/pdf/ai/*"));
        }
        return bean;
      }
    };
  }
}
