package cn.edu.pdfworkbench.auth;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.exception.ApiException;
import java.time.*;
import org.junit.jupiter.api.Test;

class AccountAttemptLimiterTest {
  static class Time extends Clock {
    Instant now = Instant.EPOCH;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @Test
  void limitsExpireAndKeysAreIndependent() {
    var time = new Time();
    var limiter = new AccountAttemptLimiter(time);
    for (int i = 0; i < 5; i++) limiter.check("a", 5, true);
    assertEquals(
        429, assertThrows(ApiException.class, () -> limiter.check("a", 5, false)).status());
    limiter.check("b", 5, true);
    time.now = time.now.plusSeconds(901);
    assertDoesNotThrow(() -> limiter.check("a", 5, true));
    limiter.clear("a");
    assertDoesNotThrow(() -> limiter.check("a", 1, true));
  }

  @Test
  void memoryHasABound() {
    var limiter = new AccountAttemptLimiter();
    for (int i = 0; i < 2048; i++) limiter.check("key-" + i, 10, true);
    assertThrows(ApiException.class, () -> limiter.check("overflow", 10, true));
  }
}
