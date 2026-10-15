package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.AiModelClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 通过 -Dtest=AiModelLiveIT 显式执行真实模型契约验证。 */
@SpringBootTest
class AiModelLiveIT {
  @Autowired AiModelClient models;

  @Test
  void chatEmbeddingAndRerankUseConfiguredProvider() {
    var vectors = models.embed(List.of("文档说明 Alpha 的数值是 42。", "文档说明 Beta 的数值是 17。"));
    assertEquals(2, vectors.size());
    assertEquals(1024, vectors.getFirst().length);
    assertEquals(
        0, models.rerank("Alpha 是多少？", List.of("Alpha 的数值是 42。", "Beta 的数值是 17。")).getFirst());
    String result = models.complete("只依据证据简短回答，不添加其他内容。", "证据：Alpha 的数值是 42。问题：Alpha 是多少？");
    assertTrue(result.contains("42"));
  }
}
