package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.AiIntent;
import cn.edu.pdfworkbench.ai.AiModelClient;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 通过 -Dtest=AiModelLiveIT 显式执行真实模型契约验证。 */
@SpringBootTest
class AiModelLiveIT {
  @Autowired AiModelClient models;
  @Autowired AiIntent intents;

  @Test
  void naturalLanguageSelectsReadingOperationAndScope() {
    var content =
        new Content(
            List.of(
                new Chunk("p1-0", 1, 0, 8, "Overview", List.of()),
                new Chunk("p4-0", 4, 0, 8, "Evidence", List.of())),
            List.of(
                new Chapter("intro", "第一章 概述", 1, 0, 3, 100, "BOOKMARK"),
                new Chapter("methods", "第二章 方法", 4, 0, 6, 100, "BOOKMARK")));
    assertEquals("BRIEF", intents.resolve("帮我概括这份文档的主要内容", content, 6, List.of()).mode());
    assertEquals("DETAILED", intents.resolve("按章节梳理文档结构和重点", content, 6, List.of()).mode());
    assertEquals("ASK", intents.resolve("解释文档中的关键概念", content, 6, List.of()).mode());
    var scoped = intents.resolve("分析 PDF 第 3–5 页的内容并总结重点", content, 6, List.of());
    assertEquals("CHAPTER", scoped.mode());
    assertEquals("3-5", scoped.pages());
    var chapter = intents.resolve("总结第二章 方法", content, 6, List.of());
    assertEquals("CHAPTER", chapter.mode());
    assertEquals("methods", chapter.chapterId());
    assertEquals("CLARIFY", intents.resolve("帮我分析这一章", content, 6, List.of()).mode());
    assertEquals("OUTLINE", intents.resolve("只列出文档目录", content, 6, List.of()).mode());
  }

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
