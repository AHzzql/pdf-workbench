package cn.edu.pdfworkbench.example;

import java.io.IOException;
import java.nio.file.*;

final class ExampleInput {
  final Path input, output;
  final String password = System.getenv("PDF_INPUT_PASSWORD");

  ExampleInput(String[] args) throws IOException {
    if (args.length < 2) throw new IllegalArgumentException("参数：输入 PDF 路径 输出目录");
    input = Path.of(args[0]);
    output = Files.createDirectories(Path.of(args[1]));
  }

  static String secret(String name) {
    String value = System.getenv(name);
    if (value == null) throw new IllegalArgumentException("缺少环境变量：" + name);
    return value;
  }
}
