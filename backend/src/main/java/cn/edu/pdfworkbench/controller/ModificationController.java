package cn.edu.pdfworkbench.controller;

import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.service.PdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(
    value = "/api/v1/pdf",
    consumes = "multipart/form-data",
    produces = "application/pdf")
@Tag(name = "文档修改")
public class ModificationController {
  public enum WatermarkType {
    TEXT,
    IMAGE
  }

  private final PdfService service;

  public ModificationController(PdfService service) {
    this.service = service;
  }

  @PostMapping("/encrypt")
  @Operation(summary = "AES 加密及权限设置")
  public void encrypt(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(defaultValue = "") String userPassword,
      @RequestParam String ownerPassword,
      @RequestParam(defaultValue = "256") int keyLength,
      @RequestParam(defaultValue = "true") boolean allowPrint,
      @RequestParam(defaultValue = "true") boolean allowCopy,
      @RequestParam(defaultValue = "true") boolean allowModify,
      HttpServletResponse response)
      throws IOException {
    service.encrypt(
        file,
        password,
        new EncryptionOptions(
            userPassword, ownerPassword, keyLength, allowPrint, allowCopy, allowModify),
        response);
  }

  @PostMapping("/watermark")
  @Operation(summary = "添加文字或图片水印", description = "位置采用 PDF 默认用户空间；角度为逆时针度数。")
  public void watermark(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam WatermarkType type,
      @RequestParam(required = false) String pages,
      @RequestParam(required = false) String text,
      @RequestPart(required = false) MultipartFile image,
      @RequestParam(defaultValue = "60") float x,
      @RequestParam(defaultValue = "60") float y,
      @RequestParam(defaultValue = "28") float fontSize,
      @RequestParam(defaultValue = "3355443") int rgb,
      @RequestParam(defaultValue = "160") float width,
      @RequestParam(defaultValue = "100") float height,
      @RequestParam(defaultValue = "30") float angle,
      @RequestParam(defaultValue = "0.3") float opacity,
      HttpServletResponse response)
      throws IOException {
    service.watermark(
        file,
        password,
        type == WatermarkType.TEXT
            ? new TextWatermark(pages, text, x, y, fontSize, rgb, angle, opacity)
            : null,
        new ImageWatermark(pages, null, x, y, width, height, angle, opacity),
        image,
        response);
  }
}
