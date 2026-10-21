package cn.edu.pdfworkbench.exception;

import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorBody> media(Exception e) {
    return error(415, "UNSUPPORTED_FORMAT", "请求正文的媒体类型与接口要求不一致");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorBody> method(Exception e) {
    return error(405, "METHOD_NOT_ALLOWED", "不支持此请求方法");
  }

  public record ErrorBody(int status, String code, String message) {}

  @ExceptionHandler(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class)
  ResponseEntity<ErrorBody> password(Exception e) {
    return error(403, "PDF_PASSWORD_INVALID", "PDF 打开密码不正确");
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<ErrorBody> body(Exception e) {
    return error(400, "INVALID_BODY", "请求正文格式不正确");
  }

  private ResponseEntity<ErrorBody> error(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorBody(status, code, message));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> api(ApiException e) {
    if (e.status() == 429)
      return ResponseEntity.status(429)
          .header("Retry-After", "900")
          .body(new ErrorBody(429, e.code(), e.getMessage()));
    return error(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(PdfProcessingException.class)
  ResponseEntity<ErrorBody> pdf(PdfProcessingException e) {
    int status = switch (e.getCode()) {
      case "OUTPUT_CREATE_FAILED" -> 500;
      case "OUTPUT_LIMIT_EXCEEDED", "INPUT_LIMIT_EXCEEDED" -> 413;
      default -> e.getCode().contains("PASSWORD") || e.getCode().contains("PERMISSION") ? 403 : 422;
    };
    return error(status, e.getCode(), e.getMessage());
  }

  @ExceptionHandler(PdfParameterException.class)
  ResponseEntity<ErrorBody> parameter(PdfParameterException e) {
    return error(400, e.getCode(), e.getMessage());
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ErrorBody> parameter(Exception e) {
    return error(400, "INVALID_PARAMETER", "请求参数缺失或格式不正确");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> large(Exception e) {
    return error(413, "FILE_TOO_LARGE", "上传文件超过大小限制");
  }

  @ExceptionHandler(IOException.class)
  ResponseEntity<ErrorBody> io(IOException e) {
    return error(422, "DOCUMENT_INVALID", "文件无法解析");
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<ErrorBody> storage(Exception e) {
    return error(503, "DATA_STORAGE_UNAVAILABLE", "数据服务暂不可用，请稍后重试");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> unknown(Exception e) {
    return error(500, "INTERNAL_ERROR", "处理失败");
  }
}
