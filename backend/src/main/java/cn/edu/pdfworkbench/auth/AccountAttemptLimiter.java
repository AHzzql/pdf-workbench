package cn.edu.pdfworkbench.auth;

import cn.edu.pdfworkbench.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class AccountAttemptLimiter {
  private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
      local count = tonumber(redis.call('GET', KEYS[1]) or '0')
      if count >= tonumber(ARGV[1]) then return 0 end
      if ARGV[2] == '1' then
        count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('PEXPIRE', KEYS[1], 900000) end
      end
      return 1
      """, Long.class);
  private final StringRedisTemplate redis;
  private final String namespace;

  public AccountAttemptLimiter(StringRedisTemplate redis,
      @Value("${spring.session.redis.namespace:pdf-workbench:session:v1}") String namespace) {
    this.redis = redis;
    this.namespace = namespace;
  }

  private String key(String value) {
    try {
      return namespace + ":attempts:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  public void check(String value, int limit, boolean consume) {
    try {
      Long result = redis.execute(CHECK, List.of(key(value)), Integer.toString(limit), consume ? "1" : "0");
      if (result == null) throw new ApiException(503, "ACCOUNT_LIMITER_UNAVAILABLE", "账户服务暂不可用，请稍后重试");
      if (result == 0) throw new ApiException(429, "ACCOUNT_TOO_MANY_ATTEMPTS", "操作过于频繁，请在 15 分钟后重试");
    } catch (DataAccessException e) {
      throw new ApiException(503, "ACCOUNT_LIMITER_UNAVAILABLE", "账户服务暂不可用，请稍后重试");
    }
  }

  public void clear(String value) {
    try { redis.delete(key(value)); }
    catch (DataAccessException e) { /* 计数仍按原 TTL 到期，已完成的业务操作不回滚。 */ }
  }
}
