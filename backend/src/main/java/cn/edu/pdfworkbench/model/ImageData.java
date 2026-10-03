package cn.edu.pdfworkbench.model;

import java.util.List;

/** 图片原始编码流及绘制位置，原始流不一定是独立图片文件。 */
public record ImageData(
    String id,
    String source,
    int width,
    int height,
    String filters,
    byte[] rawData,
    List<Occurrence> occurrences) {
  public ImageData {
    rawData = rawData.clone();
    occurrences = List.copyOf(occurrences);
  }

  @Override
  public byte[] rawData() {
    return rawData.clone();
  }

  public record Occurrence(int page, Rect bounds) {}
}
