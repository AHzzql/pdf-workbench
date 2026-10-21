package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.config.SessionStorageFailureFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.*;

class SessionStorageFailureFilterTest {
  @Test
  void unavailableSessionStoreReturnsStableNonCacheableErrorWithoutCauseDetails() throws Exception {
    var filter = new SessionStorageFailureFilter(new ObjectMapper());
    var response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest(), response, (request, result) -> {
      throw new RedisConnectionFailureException("internal-fixture-detail");
    });
    assertEquals(503, response.getStatus());
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertTrue(response.getContentAsString().contains("SESSION_STORAGE_UNAVAILABLE"));
    assertFalse(response.getContentAsString().contains("internal-fixture-detail"));
  }

  @Test
  void committedStreamIsNotRewrittenAsJson() throws Exception {
    var response = new MockHttpServletResponse();
    response.getWriter().write("data: fixture\n\n");
    response.flushBuffer();
    var filter = new SessionStorageFailureFilter(new ObjectMapper());
    assertThrows(RedisConnectionFailureException.class, () -> filter.doFilter(new MockHttpServletRequest(), response,
        (request, result) -> { throw new RedisConnectionFailureException("fixture"); }));
    assertEquals("data: fixture\n\n", response.getContentAsString());
  }
}
