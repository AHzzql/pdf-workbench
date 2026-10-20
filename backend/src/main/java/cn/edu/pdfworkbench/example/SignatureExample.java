package cn.edu.pdfworkbench.example;

import cn.edu.pdfworkbench.model.SigningOptions;
import cn.edu.pdfworkbench.util.PdfSignatureUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** 参数：输入 PDF、输出目录、PKCS#12 路径；CERTIFICATE_PASSWORD 为证书密码。 */
public final class SignatureExample {
  private SignatureExample() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("参数：输入 PDF 输出目录 PKCS#12 路径");
    var input = new ExampleInput(args);
    var signed = input.output.resolve("signed.pdf");
    char[] password = ExampleInput.secret("CERTIFICATE_PASSWORD").toCharArray();
    try (var options =
        new SigningOptions(Path.of(args[2]), password, null, "Document approval", null)) {
      PdfSignatureUtils.sign(input.input, input.password, signed, options);
    } finally {
      Arrays.fill(password, '\0');
    }
    new ObjectMapper()
        .findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .writeValue(
            input.output.resolve("verification.json").toFile(),
            PdfSignatureUtils.verify(signed, input.password, List.of()));
    PdfSignatureUtils.remove(signed, input.password, input.output.resolve("unsigned.pdf"));
  }
}
