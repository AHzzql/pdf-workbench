package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.util.PdfTextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 参数：输入 PDF、输出目录；可选 PDF_INPUT_PASSWORD 环境变量。 */
public final class TextExample {
  private TextExample() {}

  public static void main(String[] args) throws Exception {
    var input = new ExampleInput(args);
    var mapper = new ObjectMapper();
    mapper.writeValue(
        input.output.resolve("text.json").toFile(),
        PdfTextUtils.extract(input.input, input.password, new TextOptions(null, true)));
    mapper.writeValue(
        input.output.resolve("search.json").toFile(),
        PdfTextUtils.search(input.input, input.password, new SearchOptions(null, "\\p{L}+", 0)));
  }
}
