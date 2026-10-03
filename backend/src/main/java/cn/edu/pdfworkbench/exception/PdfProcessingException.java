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

  public String getCode() {
    return code;
  }
}
