package cn.edu.pdfworkbench.controller;

import cn.edu.pdfworkbench.service.PdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value = "/api/v1/pdf/signatures", consumes = "multipart/form-data")
@Tag(name = "数字签名")
public class SignatureController {
  private final PdfService service;

  public SignatureController(PdfService service) {
    this.service = service;
  }

  @PostMapping(value = "/sign", produces = "application/pdf")
  @Operation(summary = "使用 PKCS#12 证书签署 PDF")
  public void sign(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestPart MultipartFile certificate,
      @RequestParam String certificatePassword,
      @RequestParam(required = false) String alias,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) String location,
      HttpServletResponse response)
      throws IOException {
    service.sign(
        file, password, certificate, certificatePassword, alias, reason, location, response);
  }

  @PostMapping(value = "/verify", produces = "application/json")
  @Operation(summary = "验证签名完整性、修订、有效期和证书信任")
  public void verify(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestPart(required = false) MultipartFile trustCertificates,
      HttpServletResponse response)
      throws IOException {
    service.verify(file, password, trustCertificates, response);
  }

  @PostMapping(value = "/remove", produces = "application/pdf")
  @Operation(summary = "移除全部签名", description = "仅支持未加密文档，保留普通表单及批注。")
  public void remove(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam boolean confirmRemoval,
      HttpServletResponse response)
      throws IOException {
    service.remove(file, password, confirmRemoval, response);
  }
}
