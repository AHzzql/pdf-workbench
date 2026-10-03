package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.ImageWatermark;
import cn.edu.pdfworkbench.model.PageSelection;
import cn.edu.pdfworkbench.model.TextWatermark;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/** 使用默认用户空间坐标追加水印，保留原始内容及加密策略。 */
public final class PdfWatermarkUtils {
  private PdfWatermarkUtils() {}

  /** 以嵌入中文字体在指定页追加单行文本水印。 */
  public static void addText(Path input, String password, Path output, TextWatermark options)
      throws IOException {
    if (options == null) throw new IllegalArgumentException("水印参数不能为空");
    validatePlacement(options.x(), options.y(), options.angle(), options.opacity());
    if (options.text() == null
        || options.text().isBlank()
        || options.text().length() > 512
        || options.text().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("水印必须为 1～512 个字符的单行文本");
    if (!Float.isFinite(options.fontSize())
        || options.fontSize() <= 0
        || options.fontSize() > 500
        || options.rgb() < 0
        || options.rgb() > 0xffffff) throw new IllegalArgumentException("字号或颜色非法");
    PdfFiles.validateOutput(input, output);
    try (var document = PdfFiles.open(input, password)) {
      validateDocument(document);
      var pages = PageSelection.parse(options.pages(), document.getNumberOfPages());
      PDType0Font font;
      try (var stream = PdfWatermarkUtils.class.getResourceAsStream("/fonts/NotoSansSC.ttf")) {
        if (stream == null) throw new IOException("中文字体资源不可用");
        font = PDType0Font.load(document, stream, true);
      }
      font.encode(options.text());
      for (int number : pages.pages()) {
        var page = document.getPage(number - 1);
        validatePage(page, options.x(), options.y());
        try (var content = content(document, page, options.opacity())) {
          content.beginText();
          content.setFont(font, options.fontSize());
          content.setNonStrokingColor(new Color(options.rgb()));
          content.setTextMatrix(
              Matrix.getRotateInstance(Math.toRadians(options.angle()), options.x(), options.y()));
          content.showText(options.text());
          content.endText();
          content.restoreGraphicsState();
        }
      }
      PdfFiles.saveIncremental(document, output);
    }
  }

  /** 将 PNG/JPG 等比缩放至边界内，保留图片 alpha 并叠加整体透明度。 */
  public static void addImage(Path input, String password, Path output, ImageWatermark options)
      throws IOException {
    if (options == null) throw new IllegalArgumentException("水印参数不能为空");
    validatePlacement(options.x(), options.y(), options.angle(), options.opacity());
    if (!Float.isFinite(options.width())
        || !Float.isFinite(options.height())
        || options.width() <= 0
        || options.height() <= 0
        || options.width() > 10000
        || options.height() > 10000) throw new IllegalArgumentException("图片边界尺寸必须大于 0 且不超过 10000");
    PdfFiles.validateOutput(input, output);
    BufferedImage raster = readImage(options.image());
    try (var document = PdfFiles.open(input, password)) {
      validateDocument(document);
      var pages = PageSelection.parse(options.pages(), document.getNumberOfPages());
      var image = LosslessFactory.createFromImage(document, raster);
      float scale =
          Math.min(options.width() / raster.getWidth(), options.height() / raster.getHeight());
      for (int number : pages.pages()) {
        var page = document.getPage(number - 1);
        validatePage(page, options.x(), options.y());
        try (var content = content(document, page, options.opacity())) {
          content.transform(
              Matrix.getRotateInstance(Math.toRadians(options.angle()), options.x(), options.y()));
          content.drawImage(image, 0, 0, raster.getWidth() * scale, raster.getHeight() * scale);
          content.restoreGraphicsState();
        }
      }
      PdfFiles.saveIncremental(document, output);
    } finally {
      raster.flush();
    }
  }

  private static BufferedImage readImage(Path file) throws IOException {
    if (file == null || !Files.isRegularFile(file) || Files.size(file) > 20L * 1024 * 1024)
      throw new IllegalArgumentException("水印图片不存在或超过 20 MiB");
    try (var stream = new FileImageInputStream(file.toFile())) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IllegalArgumentException("水印仅支持 PNG/JPG 图片");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!"PNG".equalsIgnoreCase(format) && !"JPEG".equalsIgnoreCase(format))
          throw new IllegalArgumentException("水印仅支持 PNG/JPG 图片");
        reader.setInput(stream, true, true);
        if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000)
          throw new IllegalArgumentException("水印图片像素超过限制");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private static void validatePlacement(float x, float y, float angle, float opacity) {
    if (!Float.isFinite(x)
        || !Float.isFinite(y)
        || !Float.isFinite(angle)
        || Math.abs(angle) > 360
        || !Float.isFinite(opacity)
        || opacity < 0
        || opacity > 1) throw new IllegalArgumentException("位置、角度或透明度非法");
  }

  private static void validatePage(PDPage page, float x, float y) {
    var box = page.getCropBox();
    if (x < box.getLowerLeftX()
        || x > box.getUpperRightX()
        || y < box.getLowerLeftY()
        || y > box.getUpperRightY()) throw new IllegalArgumentException("水印起点超出页面裁剪区域");
  }

  private static void validateDocument(PDDocument document) throws IOException {
    if (!document.getCurrentAccessPermission().canModify())
      throw new PdfProcessingException("PDF_PERMISSION_DENIED", "文档不允许修改");
    if (!document.getSignatureDictionaries().isEmpty())
      throw new PdfProcessingException("PDF_SIGNED", "已签名文档不能添加水印");
  }

  private static PDPageContentStream content(PDDocument document, PDPage page, float opacity)
      throws IOException {
    var content =
        new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true);
    try {
      content.saveGraphicsState();
      var state = new PDExtendedGraphicsState();
      state.setNonStrokingAlphaConstant(opacity);
      state.setStrokingAlphaConstant(opacity);
      content.setGraphicsStateParameters(state);
      return content;
    } catch (IOException | RuntimeException exception) {
      try {
        content.close();
      } catch (IOException close) {
        exception.addSuppressed(close);
      }
      throw exception;
    }
  }
}
