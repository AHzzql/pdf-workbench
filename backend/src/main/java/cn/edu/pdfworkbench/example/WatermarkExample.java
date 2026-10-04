package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.util.PdfWatermarkUtils;
import java.nio.file.Path;

/** 参数：输入 PDF、输出目录、PNG/JPG 路径。 */
public final class WatermarkExample {
  private WatermarkExample() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("参数：输入 PDF 输出目录 PNG/JPG 路径");
    var input = new ExampleInput(args);
    PdfWatermarkUtils.addText(
        input.input,
        input.password,
        input.output.resolve("text-watermark.pdf"),
        new TextWatermark(null, "内部资料", 60, 60, 28, 0x555555, 30, 0.3f));
    PdfWatermarkUtils.addImage(
        input.input,
        input.password,
        input.output.resolve("image-watermark.pdf"),
        new ImageWatermark(null, Path.of(args[2]), 60, 60, 160, 100, 30, 0.3f));
  }
}
