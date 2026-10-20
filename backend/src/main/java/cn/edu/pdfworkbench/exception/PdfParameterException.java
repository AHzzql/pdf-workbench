package cn.edu.pdfworkbench.exception;

/** 保留参数问题类别，且兼容工具方法的 IllegalArgumentException 契约。 */
public final class PdfParameterException extends IllegalArgumentException {
  private final String code;

  public PdfParameterException(String code, String message) {
    super(message);
    this.code = code;
  }

  public PdfParameterException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String getCode() { return code; }
}
