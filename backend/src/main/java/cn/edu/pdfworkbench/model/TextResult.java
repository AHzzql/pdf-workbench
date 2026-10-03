package cn.edu.pdfworkbench.model;

import java.util.List;

/** 分页文字及可独立于 PDF 文档保存的几何数据。 */
public record TextResult(List<PageText> pages) {
  public TextResult {
    pages = List.copyOf(pages);
  }

  public record PageText(
      int page,
      Rect cropBox,
      int rotation,
      float userUnit,
      String text,
      List<TextSpan> spans,
      List<Paragraph> paragraphs,
      List<String> diagnostics) {
    public PageText {
      spans = List.copyOf(spans);
      paragraphs = List.copyOf(paragraphs);
      diagnostics = List.copyOf(diagnostics);
    }
  }

  public record TextColor(String colorSpace, List<Float> components, String rgb) {
    public TextColor {
      components = List.copyOf(components);
    }
  }

  public record TextSpan(
      String text,
      int start,
      int end,
      Rect bounds,
      float baselineY,
      String font,
      float fontSize,
      TextColor fillColor,
      TextColor strokeColor,
      int renderingMode) {}

  public record Paragraph(String text, int start, int end) {}
}
