package cn.edu.pdfworkbench.tasks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record TaskSettings(
    @Value("${tasks.namespace:pdf-workbench.tasks.v1}") String namespace,
    @Value("${tasks.role:web}") String role,
    @Value("${tasks.lease-seconds:30}") int leaseSeconds,
    @Value("${tasks.max-pending:128}") int maxPending,
    @Value("${tasks.max-attempts:3}") int maxAttempts,
    @Value("${tasks.timeout-seconds:900}") int timeoutSeconds) {
  public TaskSettings {
    if (!namespace.matches("pdf-workbench[.a-z0-9-]{1,160}") || !java.util.Set.of("web","worker","all").contains(role)
        || leaseSeconds < 2 || maxPending < 2 || maxPending > 10000 || maxAttempts < 1 || maxAttempts > 10 || timeoutSeconds < leaseSeconds || timeoutSeconds > 7200)
      throw new IllegalArgumentException("Invalid task configuration");
  }
  public boolean consumes() { return !role.equals("web"); }
  public String queue(String route) { return namespace + "." + route; }
}
