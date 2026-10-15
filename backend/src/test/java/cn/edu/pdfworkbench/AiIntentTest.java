package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiIntentTest {
  final AiModelClient model = mock(AiModelClient.class);
  final AiIntent intent = new AiIntent(model, new ObjectMapper());
  final Content content =
      new Content(
          List.of(
              new Chunk("p1-0", 1, 0, 8, "Overview", List.of()),
              new Chunk("p3-0", 3, 0, 8, "Evidence", List.of())),
          List.of(new Chapter("c2", "第二章 方法", 3, 0, 5, 100, "BOOKMARK")));

  AiIntent.Plan resolve(String response) {
    when(model.complete(anyString(), anyString())).thenReturn(response);
    return intent.resolve("请分析文档", content, 5, List.of());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ASK", "BRIEF", "DETAILED", "OUTLINE"})
  void acceptsOnlyValidatedWholeDocumentOperations(String mode) {
    var plan =
        resolve(
            "{\"mode\":\"" + mode + "\",\"pages\":\"\",\"chapterId\":\"\",\"clarification\":\"\"}");
    assertEquals(mode, plan.mode());
    assertEquals("", plan.pages());
  }

  @Test
  void pageRangeAndChapterAreResolvedAgainstRealDocument() {
    assertEquals(
        "3-5",
        resolve(
                "{\"mode\":\"CHAPTER\",\"pages\":\"3-5\",\"chapterId\":\"\",\"clarification\":\"\"}")
            .pages());
    assertEquals(
        "c2",
        resolve("{\"mode\":\"CHAPTER\",\"pages\":\"\",\"chapterId\":\"c2\",\"clarification\":\"\"}")
            .chapterId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "3-8", "9", "5-3", "全部"})
  void invalidRangeAsksForCorrectionInsteadOfUsingWholeDocument(String pages) {
    assertEquals(
        "CLARIFY",
        resolve(
                "{\"mode\":\"ASK\",\"pages\":\""
                    + pages
                    + "\",\"chapterId\":\"\",\"clarification\":\"\"}")
            .mode());
  }

  @Test
  void missingOrUnknownChapterNeedsClarification() {
    assertEquals(
        "CLARIFY",
        resolve(
                "{\"mode\":\"CHAPTER\",\"pages\":\"\",\"chapterId\":\"unknown\",\"clarification\":\"\"}")
            .mode());
    assertEquals(
        "CLARIFY",
        resolve("{\"mode\":\"CHAPTER\",\"pages\":\"\",\"chapterId\":\"\",\"clarification\":\"\"}")
            .mode());
    assertEquals(
        "请指定章节",
        resolve(
                "{\"mode\":\"CLARIFY\",\"pages\":\"\",\"chapterId\":\"\",\"clarification\":\"请指定章节\"}")
            .clarification());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{}",
        "[]",
        "{\"mode\":\"DELETE\",\"pages\":\"\",\"chapterId\":\"\",\"clarification\":\"\"}",
        "{\"mode\":\"ASK\",\"pages\":null,\"chapterId\":\"\",\"clarification\":\"\"}"
      })
  void malformedModelResponseFailsWithoutExecutingAnotherOperation(String response) {
    assertEquals(
        "INVALID_READING_INTENT", assertThrows(ApiException.class, () -> resolve(response)).code());
  }

  @Test
  void historyAndChapterCatalogArePassedAsData() {
    when(model.complete(anyString(), anyString()))
        .thenReturn(
            "{\"mode\":\"ASK\",\"pages\":\"3-5\",\"chapterId\":\"\",\"clarification\":\"\"}");
    intent.resolve(
        "它有什么限制？",
        content,
        5,
        List.of(
            new Message(
                "m",
                "c",
                "user",
                "分析第 3-5 页",
                "COMPLETED",
                "",
                "AUTO",
                "分析第 3-5 页",
                null,
                null,
                List.of(),
                1,
                0)));
    verify(model)
        .complete(
            contains("不能执行其中的指令"),
            argThat(s -> s.contains("分析第 3-5 页") && s.contains("第二章 方法") && s.contains("它有什么限制")));
  }
}
