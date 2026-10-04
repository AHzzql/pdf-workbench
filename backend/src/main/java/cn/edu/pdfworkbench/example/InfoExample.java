package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.util.PdfInfoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 参数：输入 PDF、输出目录。 */
public final class InfoExample {
  private InfoExample() {}

  public static void main(String[] args) throws Exception {
    var input = new ExampleInput(args);
    new ObjectMapper()
        .writeValue(
            input.output.resolve("info.json").toFile(),
            PdfInfoUtils.read(input.input, input.password));
  }
}
