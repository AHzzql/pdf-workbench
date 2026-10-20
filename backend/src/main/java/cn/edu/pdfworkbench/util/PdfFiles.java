package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

final class PdfFiles {
  private PdfFiles() {}

  static PDDocument openForExtraction(Path input, String password) throws IOException {
    PDDocument document = open(input, password);
    if (!document.getCurrentAccessPermission().canExtractContent()) {
      var error = new PdfProcessingException("PDF_PERMISSION_DENIED", "文档不允许提取内容");
      try {
        document.close();
      } catch (IOException closeError) {
        error.addSuppressed(closeError);
      }
      throw error;
    }
    return document;
  }

  static PDDocument open(Path input, String password) throws IOException {
    if (input == null || !Files.isRegularFile(input)) {
      throw new IllegalArgumentException("输入文件不存在");
    }
    PDDocument document;
    try {
      document = Loader.loadPDF(input.toFile(), password == null ? "" : password);
    } catch (InvalidPasswordException exception) {
      throw PdfProcessingException.password(password, exception);
    }
    return document;
  }

  static void validateOutput(Path input, Path output) throws IOException {
    if (input == null
        || output == null
        || input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
        || (Files.exists(output) && Files.isSameFile(input, output))) {
      throw new IllegalArgumentException("输出路径必须与源文件不同");
    }
    if (Files.isDirectory(output)) throw new IllegalArgumentException("输出路径不能是目录");
  }

  static Path temporaryOutput(Path output, String prefix) throws IOException {
    try {
      return Files.createTempFile(output.toAbsolutePath().getParent(), prefix, ".tmp");
    } catch (IOException exception) {
      throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "输出目录无法创建临时文件", exception);
    }
  }

  static void save(PDDocument document, Path output) throws IOException {
    Path temporary = temporaryOutput(output, "pdf-");
    try {
      document.save(temporary.toFile());
      Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException exception) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanup) {
        exception.addSuppressed(cleanup);
      }
      if (exception instanceof IOException io)
        throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "PDF 输出文件无法写入", io);
      throw exception;
    }
  }

  static void saveIncremental(PDDocument document, Path output) throws IOException {
    Path temporary = temporaryOutput(output, "pdf-");
    try {
      try (var stream = Files.newOutputStream(temporary)) {
        document.saveIncremental(stream);
      }
      Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException exception) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanup) {
        exception.addSuppressed(cleanup);
      }
      if (exception instanceof IOException io)
        throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "PDF 输出文件无法写入", io);
      throw exception;
    }
  }
}
