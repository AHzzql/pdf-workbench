package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExamplesTest {
  @TempDir Path directory;

  @Test
  void allSevenExamplesRunWithoutSpring() throws Exception {
    Path source = directory.resolve("source.pdf"),
        image = directory.resolve("image.png"),
        key = directory.resolve("key.p12");
    var raster = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(raster, "PNG", image.toFile());
    new PdfSignatureUtilsTest().certificate(key);
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var stream = new PDPageContentStream(document, page)) {
        for (int x = 60; x <= 260; x += 100) {
          stream.moveTo(x, 500);
          stream.lineTo(x, 580);
        }
        for (int y = 500; y <= 580; y += 40) {
          stream.moveTo(60, y);
          stream.lineTo(260, y);
        }
        stream.stroke();
        PdfTextUtilsTest.text(stream, "Example", 65, 560, 12, Color.BLACK);
        stream.drawImage(LosslessFactory.createFromImage(document, raster), 60, 650, 20, 10);
      }
      document.save(source.toFile());
    }
    for (String name :
        List.of("Text", "Info", "Table", "Image", "Encryption", "Signature", "Watermark")) {
      Path output = directory.resolve(name);
      var arguments =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                  "-Dfile.encoding=UTF-8",
                  "-cp",
                  System.getProperty("java.class.path"),
                  "cn.edu.pdfworkbench.example." + name + "Example",
                  source.toString(),
                  output.toString()));
      if (name.equals("Signature")) arguments.add(key.toString());
      if (name.equals("Watermark")) arguments.add(image.toString());
      Path log = directory.resolve(name + ".log");
      var builder =
          new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile());
      builder.environment().put("PDF_OWNER_PASSWORD", "owner-test");
      builder.environment().put("PDF_USER_PASSWORD", "reader-test");
      builder.environment().put("CERTIFICATE_PASSWORD", "test-password");
      var process = builder.start();
      try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(log));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      try (var files = Files.list(output)) {
        assertTrue(files.findAny().isPresent());
      }
      assertFalse(Files.readString(log).contains("Spring Boot"));
    }
    assertTrue(Files.readString(directory.resolve("Text/text.json")).contains("Example"));
    assertTrue(Files.readString(directory.resolve("Table/table-0.csv")).contains("Example"));
    try (var document = Loader.loadPDF(directory.resolve("Signature/unsigned.pdf").toFile())) {
      assertTrue(document.getSignatureDictionaries().isEmpty());
    }
    try (var document =
        Loader.loadPDF(directory.resolve("Encryption/encrypted.pdf").toFile(), "reader-test")) {
      assertFalse(document.getCurrentAccessPermission().canPrint());
    }
  }
}
