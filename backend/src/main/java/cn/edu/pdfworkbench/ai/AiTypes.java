package cn.edu.pdfworkbench.ai;

import cn.edu.pdfworkbench.model.Rect;
import java.util.List;

public final class AiTypes {
  private AiTypes() {}

  public record Document(
      String id,
      String name,
      String hash,
      String version,
      int pages,
      boolean encrypted,
      String status,
      String error,
      long createdAt) {}

  public record Chunk(String id, int page, int start, int end, String text, List<Rect> bounds) {}

  public record Chapter(
      String id, String title, int page, int offset, int endPage, int endOffset, String source) {}

  public record Content(List<Chunk> chunks, List<Chapter> chapters) {}

  public record Conversation(
      String id, String documentId, String title, long createdAt, long updatedAt) {}

  public record Citation(String id, String documentId, int page, String quote, List<Rect> bounds) {}

  public record Message(
      String id,
      String conversationId,
      String role,
      String text,
      String status,
      String error,
      String mode,
      String question,
      String pages,
      String chapterId,
      List<Citation> citations,
      long createdAt,
      long sequence) {}

  public record Ask(
      String requestId, String mode, String question, String pages, String chapterId) {}

  public record CreateConversation(String documentId) {}

  public record Rename(String title) {}

  public record DeleteConversation(boolean deleteDocument) {}

  public record HistoryQuery(String query) {}
}
