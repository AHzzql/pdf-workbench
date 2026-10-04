package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.TableOptions;
import cn.edu.pdfworkbench.util.PdfTableUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 参数：输入 PDF、输出目录。 */
public final class TableExample {
  private TableExample() {}

  public static void main(String[] args) throws Exception {
    var input = new ExampleInput(args);
    var tables = PdfTableUtils.extract(input.input, input.password, new TableOptions(null, null));
    new ObjectMapper().writeValue(input.output.resolve("tables.json").toFile(), tables);
    for (int i = 0; i < tables.size(); i++)
      PdfTableUtils.writeCsv(tables.get(i), input.output.resolve("table-" + i + ".csv"));
  }
}
