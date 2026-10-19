package cn.edu.pdfworkbench.auth;

import cn.edu.pdfworkbench.exception.ApiException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AccountAttemptLimiter {
  private record Window(long expires, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final Clock clock;

  public AccountAttemptLimiter() {
    this(Clock.systemUTC());
  }

  AccountAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  public synchronized void check(String key, int limit, boolean consume) {
    long now = clock.millis();
    windows.entrySet().removeIf(e -> e.getValue().expires() <= now);
    var w = windows.get(key);
    if (w != null && w.count() >= limit || w == null && windows.size() >= 2048)
      throw new ApiException(429, "ACCOUNT_TOO_MANY_ATTEMPTS", "操作过于频繁，请在 15 分钟后重试");
    if (consume)
      windows.put(
          key, new Window(w == null ? now + 900000 : w.expires(), w == null ? 1 : w.count() + 1));
  }

  public synchronized void clear(String key) {
    windows.remove(key);
  }
}
