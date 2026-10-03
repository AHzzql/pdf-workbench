package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.ImageFormat;
import cn.edu.pdfworkbench.util.PdfImageUtils;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfImageUtilsTest {
  @TempDir Path directory;

  @Test
  void extractsRawStreamsDeduplicatesAndExportsPixels() throws Exception {
    Path input = directory.resolve("images.pdf");
    byte[] expected;
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var raster = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      raster.setRGB(0, 0, 0xFF0000);
      var image = LosslessFactory.createFromImage(document, raster);
      try (var stream = image.getCOSObject().createRawInputStream()) {
        expected = stream.readAllBytes();
      }
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 50, 60, 20, 20);
        content.drawImage(image, 100, 60, 20, 20);
      }
      document.save(input.toFile());
    }
    var images = PdfImageUtils.extract(input, null);
    assertEquals(1, images.size());
    assertEquals(2, images.getFirst().occurrences().size());
    assertArrayEquals(expected, images.getFirst().rawData());
    Path output = Files.createDirectory(directory.resolve("png"));
    var files = PdfImageUtils.export(input, null, output, ImageFormat.PNG);
    assertNull(files.getFirst().diagnostic());
    var image = ImageIO.read(files.getFirst().file().toFile());
    assertEquals(2, image.getWidth());
    assertEquals(0xFF0000, image.getRGB(0, 0) & 0xFFFFFF);
  }

  @Test
  void exportsTransparencyAndWhiteJpegBackground() throws Exception {
    Path input = directory.resolve("alpha.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var raster = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
      var image = LosslessFactory.createFromImage(document, raster);
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0);
      }
      document.save(input.toFile());
    }
    var png =
        PdfImageUtils.export(
            input, null, Files.createDirectory(directory.resolve("png")), ImageFormat.PNG);
    var jpg =
        PdfImageUtils.export(
            input, null, Files.createDirectory(directory.resolve("jpg")), ImageFormat.JPG);
    assertEquals(0, ImageIO.read(png.getFirst().file().toFile()).getRGB(0, 0) >>> 24);
    assertEquals(0xFFFFFF, ImageIO.read(jpg.getFirst().file().toFile()).getRGB(0, 0) & 0xFFFFFF);
  }

  @Test
  void readsInlineImagesAndHandlesNoImages() throws Exception {
    Path input = directory.resolve("inline.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var content = new org.apache.pdfbox.pdmodel.common.PDStream(document);
      try (var out = content.createOutputStream()) {
        out.write(
            "q 10 0 0 10 20 30 cm BI /W 1 /H 1 /CS /RGB /BPC 8 ID "
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(new byte[] {(byte) 255, 0, 0});
        out.write("\nEI Q".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      page.setContents(content);
      document.addPage(new PDPage());
      document.save(input.toFile());
    }
    var data = PdfImageUtils.extract(input, null);
    assertEquals(1, data.size());
    assertEquals("inline", data.getFirst().source());
    assertArrayEquals(new byte[] {(byte) 255, 0, 0}, data.getFirst().rawData());
    Path empty = directory.resolve("empty.pdf");
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(empty.toFile());
    }
    assertTrue(PdfImageUtils.extract(empty, null).isEmpty());
    assertTrue(PdfImageUtils.export(empty, null, directory, ImageFormat.PNG).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> PdfImageUtils.export(input, null, directory, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfImageUtils.extract(directory.resolve("missing"), null));
  }

  @Test
  void includesNestedAndUnusedResourcesWithoutDuplicateStreams() throws Exception {
    Path input = directory.resolve("nested.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
      document.addPage(page);
      var raster = new BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB);
      var nested = LosslessFactory.createFromImage(document, raster);
      var unused =
          LosslessFactory.createFromImage(
              document, new BufferedImage(5, 6, BufferedImage.TYPE_INT_RGB));
      var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
      form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 100));
      form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
      try (var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
        stream.drawImage(nested, 10, 10, 30, 40);
      }
      page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("Unused"), unused);
      try (var stream = new PDPageContentStream(document, page)) {
        stream.drawForm(form);
      }
      document.save(input.toFile());
    }
    var data = PdfImageUtils.extract(input, null);
    assertEquals(2, data.size());
    assertEquals(
        1,
        data.stream()
            .filter(image -> image.width() == 3)
            .findFirst()
            .orElseThrow()
            .occurrences()
            .size());
    assertTrue(
        data.stream()
            .filter(image -> image.width() == 5)
            .findFirst()
            .orElseThrow()
            .occurrences()
            .isEmpty());
    byte[] copy = data.getFirst().rawData();
    byte before = copy[0];
    copy[0]++;
    assertEquals(before, data.getFirst().rawData()[0]);
  }

  @Test
  void readsInlineImagesInsideUnusedForms() throws Exception {
    Path input = directory.resolve("unused-form.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
      document.addPage(page);
      var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
      form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10, 10));
      form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
      try (var stream = form.getCOSObject().createOutputStream()) {
        stream.write(
            "BI /W 1 /H 1 /CS /RGB /BPC 8 ID "
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        stream.write(new byte[] {0, (byte) 255, 0});
        stream.write("\nEI".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("UnusedForm"), form);
      document.save(input.toFile());
    }
    var images = PdfImageUtils.extract(input, null);
    assertEquals(1, images.size());
    assertTrue(images.getFirst().occurrences().isEmpty());
    assertArrayEquals(new byte[] {0, (byte) 255, 0}, images.getFirst().rawData());
  }

  @Test
  void rejectsDamagedFilesAndInvalidOutputDirectories() throws Exception {
    Path damaged = Files.writeString(directory.resolve("broken.pdf"), "%PDF-1.7 broken");
    assertThrows(java.io.IOException.class, () -> PdfImageUtils.extract(damaged, null));
    assertThrows(
        java.io.IOException.class,
        () -> PdfImageUtils.export(damaged, null, directory, ImageFormat.PNG));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfImageUtils.export(damaged, null, damaged, ImageFormat.PNG));
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }
}
