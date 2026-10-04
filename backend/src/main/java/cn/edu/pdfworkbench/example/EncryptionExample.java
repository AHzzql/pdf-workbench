package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.EncryptionOptions;
import cn.edu.pdfworkbench.util.PdfEncryptionUtils;

/** 参数：输入 PDF、输出目录；PDF_OWNER_PASSWORD 必填，PDF_USER_PASSWORD 可选。 */
public final class EncryptionExample {
  private EncryptionExample() {}

  public static void main(String[] args) throws Exception {
    var input = new ExampleInput(args);
    PdfEncryptionUtils.encrypt(
        input.input,
        input.password,
        input.output.resolve("encrypted.pdf"),
        new EncryptionOptions(
            System.getenv("PDF_USER_PASSWORD"),
            ExampleInput.secret("PDF_OWNER_PASSWORD"),
            256,
            false,
            false,
            false));
  }
}
