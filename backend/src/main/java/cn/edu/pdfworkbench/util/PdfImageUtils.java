package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.ImageData;
import cn.edu.pdfworkbench.model.ImageExport;
import cn.edu.pdfworkbench.model.ImageFormat;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;

/** PDF 原始图片流与 PNG/JPG 导出。 */
public final class PdfImageUtils {
  private PdfImageUtils() {}

  /**
   * 提取可达图片资源和已绘制内联图片的原始编码流，重复 XObject 共用一个条目。
   *
   * @param input PDF 路径
   * @param password 打开密码
   * @return 原始数据与绘制位置，不持有 PDF 资源
   * @throws IOException 读取、权限或累计原始流容量错误
   */
  public static List<ImageData> extract(Path input, String password) throws IOException {
    try (var document = PdfFiles.openForExtraction(input, password)) {
      List<ImageData> result = new ArrayList<>();
      long total = 0;
      for (var entry : ImageCollector.collect(document)) {
        byte[] raw;
        if (entry.image instanceof PDInlineImage inline) {
          raw = inline.getData();
          if (inline.getFilters() == null || inline.getFilters().isEmpty()) {
            int components =
                inline.isStencil() ? 1 : inline.getColorSpace().getNumberOfComponents();
            long rowBytes =
                ((long) inline.getWidth() * components * inline.getBitsPerComponent() + 7) / 8;
            long expected = rowBytes * inline.getHeight();
            // 无过滤器时长度由像素布局确定，解析器可能把 EI 前的分隔空白计入数据。
            if (expected >= 0 && expected < raw.length)
              raw = java.util.Arrays.copyOf(raw, (int) expected);
          }
        } else
          try (var stream = ((COSStream) entry.image.getCOSObject()).createRawInputStream()) {
            raw = stream.readNBytes(ImageCollector.MAX_RAW_BYTES - (int) total + 1);
          }
        total += raw.length;
        if (total > ImageCollector.MAX_RAW_BYTES)
          throw new PdfProcessingException("OUTPUT_LIMIT_EXCEEDED", "图片原始数据超过 200 MiB");
        var filters = entry.image.getCOSObject().getDictionaryObject(COSName.FILTER, COSName.F);
        result.add(
            new ImageData(
                entry.id,
                entry.source,
                entry.image.getWidth(),
                entry.image.getHeight(),
                filters == null ? "" : filters.toString(),
                raw,
                entry.occurrences));
      }
      return List.copyOf(result);
    }
  }

  /**
   * 在目录下创建独立子目录导出图片，PNG 保留透明度，JPG 使用白底。
   *
   * @param input PDF 路径
   * @param password 打开密码
   * @param directory 已存在的输出目录
   * @param format PNG 或 JPG
   * @return 每个资源的输出路径或失败原因；单图解码失败不隐藏其他结果
   * @throws IOException 文件、权限或累计输出错误
   * @throws IllegalArgumentException 输出目录或格式无效
   */
  public static List<ImageExport> export(
      Path input, String password, Path directory, ImageFormat format) throws IOException {
    if (directory == null || !Files.isDirectory(directory) || format == null)
      throw new IllegalArgumentException("输出目录或图片格式无效");
    List<Path> created = new ArrayList<>();
    Path output = null;
    try (var document = PdfFiles.openForExtraction(input, password)) {
      var entries = ImageCollector.collect(document);
      if (entries.isEmpty()) return List.of();
      try { output = Files.createTempDirectory(directory, "images-"); }
      catch (IOException exception) {
        throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "图片输出目录无法创建", exception);
      }
      List<ImageExport> results = new ArrayList<>();
      long total = 0;
      for (var entry : entries) {
        var image = entry.image;
        if (image.getWidth() <= 0
            || image.getHeight() <= 0
            || (long) image.getWidth() * image.getHeight() > ImageCollector.MAX_PIXELS) {
          results.add(new ImageExport(entry.id, null, "图片像素数超出范围"));
          continue;
        }
        BufferedImage raster;
        try {
          raster = image.isStencil() ? image.getStencilImage(Color.BLACK) : image.getImage();
        } catch (IOException | IllegalArgumentException exception) {
          results.add(new ImageExport(entry.id, null, "图片数据无法解码"));
          continue;
        }
        if (format == ImageFormat.JPG) {
          var opaque =
              new BufferedImage(raster.getWidth(), raster.getHeight(), BufferedImage.TYPE_INT_RGB);
          var graphics = opaque.createGraphics();
          try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            graphics.drawImage(raster, 0, 0, null);
          } finally {
            graphics.dispose();
          }
          raster = opaque;
        }
        String extension = format.name().toLowerCase(Locale.ROOT);
        Path file = output.resolve(entry.id + "." + extension);
        created.add(file);
        try {
          if (!ImageIO.write(raster, extension, file.toFile())) throw new IOException("图片编码器不可用");
          total += Files.size(file);
        } catch (IOException exception) {
          throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "图片输出文件无法写入", exception);
        }
        if (total > ImageCollector.MAX_RAW_BYTES)
          throw new PdfProcessingException("OUTPUT_LIMIT_EXCEEDED", "图片导出超过 200 MiB");
        results.add(new ImageExport(entry.id, file, null));
      }
      return List.copyOf(results);
    } catch (IOException | RuntimeException exception) {
      for (Path file : created) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException cleanup) {
          exception.addSuppressed(cleanup);
        }
      }
      if (output != null) {
        try {
          Files.deleteIfExists(output);
        } catch (IOException cleanup) {
          exception.addSuppressed(cleanup);
        }
      }
      throw exception;
    }
  }
}
