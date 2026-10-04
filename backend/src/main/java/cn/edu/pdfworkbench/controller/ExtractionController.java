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
@RequestMapping(value = "/api/v1/pdf", consumes = "multipart/form-data")
@Tag(name = "文档解析")
public class ExtractionController {
  private final PdfService service;

  public ExtractionController(PdfService service) {
    this.service = service;
  }

  @PostMapping(value = "/text", produces = "application/json")
  @Operation(summary = "分页提取文本、字体、颜色与坐标")
  public void text(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(required = false) String pages,
      @RequestParam(defaultValue = "true") boolean preserveParagraphs,
      HttpServletResponse response)
      throws IOException {
    service.text(file, password, new TextOptions(pages, preserveParagraphs), response);
  }

  @PostMapping(value = "/text/search", produces = "application/json")
  @Operation(summary = "正则搜索文本")
  public void search(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(required = false) String pages,
      @RequestParam String pattern,
      @RequestParam(defaultValue = "0") int flags,
      HttpServletResponse response)
      throws IOException {
    service.search(file, password, new SearchOptions(pages, pattern, flags), response);
  }

  @PostMapping(value = "/info", produces = "application/json")
  @Operation(summary = "读取元数据、书签及批注")
  public void info(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      HttpServletResponse response)
      throws IOException {
    service.info(file, password, response);
  }

  @PostMapping(value = "/tables", produces = "application/json")
  @Operation(summary = "解析规则网格表格")
  public void tables(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(required = false) String pages,
      @RequestParam(required = false) Float x,
      @RequestParam(required = false) Float y,
      @RequestParam(required = false) Float width,
      @RequestParam(required = false) Float height,
      HttpServletResponse response)
      throws IOException {
    service.tables(
        file, password, new TableOptions(pages, region(x, y, width, height)), null, response);
  }

  @PostMapping(value = "/tables/csv", produces = "text/csv")
  @Operation(summary = "导出指定表格 CSV", description = "tableIndex 从 0 开始；公式前缀添加单引号。")
  public void csv(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(required = false) String pages,
      @RequestParam int tableIndex,
      @RequestParam(required = false) Float x,
      @RequestParam(required = false) Float y,
      @RequestParam(required = false) Float width,
      @RequestParam(required = false) Float height,
      HttpServletResponse response)
      throws IOException {
    service.tables(
        file, password, new TableOptions(pages, region(x, y, width, height)), tableIndex, response);
  }

  @PostMapping(value = "/images", produces = "application/zip")
  @Operation(summary = "导出图片原始流、PNG/JPG 及来源清单")
  public void images(
      @RequestPart MultipartFile file,
      @RequestParam(required = false) String password,
      @RequestParam(defaultValue = "PNG") ImageFormat format,
      HttpServletResponse response)
      throws IOException {
    service.images(file, password, format, response);
  }

  private static Rect region(Float x, Float y, Float width, Float height) {
    if (x == null && y == null && width == null && height == null) return null;
    if (x == null || y == null || width == null || height == null)
      throw new IllegalArgumentException("区域参数不完整");
    return new Rect(x, y, width, height);
  }
}
