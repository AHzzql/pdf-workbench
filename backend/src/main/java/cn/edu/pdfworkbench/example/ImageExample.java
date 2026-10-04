package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.ImageFormat;
import cn.edu.pdfworkbench.util.PdfImageUtils;
import java.nio.file.Files;

/** 参数：输入 PDF、输出目录。 */
public final class ImageExample {
  private ImageExample() {}

  public static void main(String[] args) throws Exception {
    var input = new ExampleInput(args);
    for (var image : PdfImageUtils.extract(input.input, input.password))
      Files.write(input.output.resolve(image.id() + ".bin"), image.rawData());
    PdfImageUtils.export(input.input, input.password, input.output, ImageFormat.PNG);
    PdfImageUtils.export(input.input, input.password, input.output, ImageFormat.JPG);
  }
}
