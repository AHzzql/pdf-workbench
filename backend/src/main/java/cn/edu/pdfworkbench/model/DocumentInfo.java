package cn.edu.pdfworkbench.model;

import java.util.List;
import java.util.Map;

/** 文档目录、元数据及批注，不持有底层 PDF 对象。 */
public record DocumentInfo(
    Map<String, String> standardMetadata,
    Map<String, String> customMetadata,
    String xmp,
    List<Bookmark> bookmarks,
    List<AnnotationData> annotations,
    List<String> diagnostics) {
  public DocumentInfo {
    standardMetadata = Map.copyOf(standardMetadata);
    customMetadata = Map.copyOf(customMetadata);
    bookmarks = List.copyOf(bookmarks);
    annotations = List.copyOf(annotations);
    diagnostics = List.copyOf(diagnostics);
  }

  public record Bookmark(String title, Integer page, String externalUri, List<Bookmark> children) {
    public Bookmark {
      children = List.copyOf(children);
    }
  }

  public record AnnotationData(
      int page, String type, String author, String contents, Rect bounds, List<Float> quadPoints) {
    public AnnotationData {
      quadPoints = List.copyOf(quadPoints);
    }
  }
}
