package cn.edu.pdfworkbench.exception;

import java.io.IOException;

/** PDF 处理失败及可供调用层判断的稳定原因码。 */
public final class PdfProcessingException extends IOException {
  private final String code;

  public PdfProcessingException(String code, String message) {
    super(message);
    this.code = code;
  }

  public PdfProcessingException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public static PdfProcessingException password(String password, IOException cause) {
    boolean missing = password == null || password.isEmpty();
    return new PdfProcessingException(missing ? "PDF_PASSWORD_REQUIRED" : "PDF_PASSWORD_INVALID",
        missing ? "PDF 需要打开密码" : "PDF 打开密码错误", cause);
  }

  public String getCode() {
    return code;
  }
}
