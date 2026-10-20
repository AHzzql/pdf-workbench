package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.ai.AiChunker;
import cn.edu.pdfworkbench.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiChunkerTest {
  @Test
  void chunksKeepSourceOffsetsAndGeometry() {
    String text = "第一章 概述\n" + "中文 PDF 阅读与证据定位。".repeat(100);
    var span =
        new TextResult.TextSpan(
            text, 0, text.length(), new Rect(10, 20, 100, 14), 20, "font", 12, null, null, 0);
    var page =
        new TextResult.PageText(
            1,
            new Rect(0, 0, 600, 800),
            0,
            1,
            text,
            List.of(span),
            List.of(),
            List.of(new TextResult.Paragraph(text, 0, text.length())),
            List.of());
    var result = AiChunker.split(new TextResult(List.of(page)), List.of());
    assertTrue(result.chunks().size() > 1);
    for (var chunk : result.chunks()) {
      assertEquals(text.substring(chunk.start(), chunk.end()), chunk.text());
      assertFalse(chunk.bounds().isEmpty());
      assertTrue(chunk.text().length() <= 1200);
    }
    assertEquals("识别目录", result.chapters().getFirst().source());
  }

  @Test
  void emptyTextProducesNoInventedChapters() {
    assertTrue(AiChunker.split(new TextResult(List.of()), List.of()).chapters().isEmpty());
  }
}
