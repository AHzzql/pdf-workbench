package cn.edu.pdfworkbench.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.pdfworkbench.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class AccountAttemptLimiterTest {
  @Test
  void limiterRejectsTheSharedStoreDecision() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
    var limiter = new AccountAttemptLimiter(redis, "pdf-workbench:test:limiter");
    assertEquals(429, assertThrows(ApiException.class, () -> limiter.check("key",5,false)).status());
  }

  @Test
  void unavailableStoreFailsClosedAndCleanupDoesNotUndoCompletedOperations() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString(), anyString()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("fixture"));
    var limiter = new AccountAttemptLimiter(redis, "pdf-workbench:test:limiter");
    assertEquals(503, assertThrows(ApiException.class, () -> limiter.check("key",5,true)).status());
    when(redis.delete(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("fixture"));
    assertDoesNotThrow(() -> limiter.clear("key"));
  }
}
