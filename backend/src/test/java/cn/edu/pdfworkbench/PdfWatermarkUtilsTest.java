package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.ImageWatermark;
import cn.edu.pdfworkbench.model.TextWatermark;
import cn.edu.pdfworkbench.util.PdfWatermarkUtils;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PdfWatermarkUtilsTest {
  @TempDir Path directory;

  private Path sample(int rotation) throws Exception {
    Path source = directory.resolve("source.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(240, 200));
      page.setCropBox(new PDRectangle(10, 20, 200, 160));
      page.setRotation(rotation);
      document.addPage(page);
      document.addPage(new PDPage(new PDRectangle(240, 200)));
      document.save(source.toFile());
    }
    return source;
  }

  @Test
  void writesChineseOnSelectedPageAndPreservesOtherPage() throws Exception {
    Path input = sample(0), output = directory.resolve("text.pdf");
    byte[] original = Files.readAllBytes(input);
    PdfWatermarkUtils.addText(
        input, null, output, new TextWatermark("1", "内部资料", 30, 60, 20, 0x225588, 0, 0.5f));
    assertArrayEquals(original, Files.readAllBytes(input));
    try (var before = Loader.loadPDF(input.toFile());
        var after = Loader.loadPDF(output.toFile())) {
      var stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      assertTrue(stripper.getText(after).contains("内部资料"));
      stripper.setStartPage(2);
      stripper.setEndPage(2);
      assertTrue(stripper.getText(after).isBlank());
      assertArrayEquals(
          new PDFRenderer(before).renderImage(1).getRGB(0, 0, 240, 200, null, 0, 240),
          new PDFRenderer(after).renderImage(1).getRGB(0, 0, 240, 200, null, 0, 240));
      var image = new PDFRenderer(after).renderImage(0);
      Path evidence = Files.createDirectories(Path.of("target", "verification"));
      ImageIO.write(
          new PDFRenderer(after).renderImage(0, 3),
          "PNG",
          evidence.resolve("watermark-chinese.png").toFile());
      long ink =
          java.util.Arrays.stream(
                  image.getRGB(
                      0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
              .filter(pixel -> (pixel & 0xffffff) != 0xffffff)
              .count();
      assertTrue(ink > 100);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 90, 180, 270})
  void imagePlacementUsesDefaultUserSpaceAcrossPageRotations(int rotation) throws Exception {
    Path input = sample(rotation),
        output = directory.resolve("image.pdf"),
        png = directory.resolve("mark.png");
    var mark = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) mark.setRGB(x, y, Color.RED.getRGB());
    ImageIO.write(mark, "PNG", png.toFile());
    PdfWatermarkUtils.addImage(
        input, null, output, new ImageWatermark("1", png, 30, 40, 40, 40, 0, 0.5f));
    try (var document = Loader.loadPDF(output.toFile())) {
      var image = new PDFRenderer(document).renderImage(0);
      // 未旋转 CropBox 内，红色半幅中心为 (30,130)，右侧透明半幅中心为 (50,130)。
      int x = 30, y = 130, tx = 50, ty = 130;
      if (rotation == 90) {
        x = 29;
        y = 30;
        tx = 29;
        ty = 50;
      }
      if (rotation == 180) {
        x = 169;
        y = 29;
        tx = 149;
        ty = 29;
      }
      if (rotation == 270) {
        x = 130;
        y = 169;
        tx = 130;
        ty = 149;
      }
      var red = new Color(image.getRGB(x, y));
      assertEquals(255, red.getRed());
      assertTrue(red.getGreen() >= 125 && red.getGreen() <= 130);
      assertEquals(0xffffff, image.getRGB(tx, ty) & 0xffffff);
    }
  }

  @Test
  void rejectsInvalidParametersImagesAndMissingGlyphs() throws Exception {
    Path input = sample(0), output = directory.resolve("out.pdf");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input, null, output, new TextWatermark(null, "", 30, 40, 20, 0, 0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input,
                null,
                output,
                new TextWatermark(null, "hello", 30, 40, 20, 0, Float.NaN, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input, null, output, new TextWatermark(null, "hello", 30, 40, 20, 0, 0, 2)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input, null, output, new TextWatermark("3", "hello", 30, 40, 20, 0, 0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input, null, output, new TextWatermark(null, "\uDBFF\uDFFF", 30, 40, 20, 0, 0, 1)));
    Path bad = Files.writeString(directory.resolve("bad.png"), "not an image");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addImage(
                input, null, output, new ImageWatermark(null, bad, 30, 40, 40, 20, 0, 1)));
    assertFalse(Files.exists(output));
  }

  @Test
  void supportsJpegRotationAndPreservesEncryption() throws Exception {
    Path input = sample(0),
        encrypted = directory.resolve("encrypted.pdf"),
        output = directory.resolve("out.pdf"),
        jpg = directory.resolve("mark.jpg");
    var mark = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
    var graphics = mark.createGraphics();
    graphics.setColor(Color.BLUE);
    graphics.fillRect(0, 0, 20, 10);
    graphics.dispose();
    assertTrue(ImageIO.write(mark, "JPG", jpg.toFile()));
    cn.edu.pdfworkbench.util.PdfEncryptionUtils.encrypt(
        input,
        null,
        encrypted,
        new cn.edu.pdfworkbench.model.EncryptionOptions(
            "reader", "owner", 256, true, false, false));
    var options = new ImageWatermark("1", jpg, 80, 60, 40, 20, 90, 1);
    assertThrows(
        java.io.IOException.class,
        () -> PdfWatermarkUtils.addImage(encrypted, "reader", output, options));
    PdfWatermarkUtils.addImage(encrypted, "owner", output, options);
    assertThrows(
        org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class,
        () -> Loader.loadPDF(output.toFile()));
    try (var document = Loader.loadPDF(output.toFile(), "reader")) {
      assertTrue(document.isEncrypted());
      assertFalse(document.getCurrentAccessPermission().canModify());
      assertFalse(document.getCurrentAccessPermission().canExtractContent());
      var image = new PDFRenderer(document).renderImage(0);
      var blue = new Color(image.getRGB(60, 100));
      assertTrue(blue.getBlue() > 240);
      assertTrue(blue.getRed() < 10);
      assertEquals(0xffffff, image.getRGB(85, 100) & 0xffffff);
    }
  }

  @Test
  void rejectsSignedInputAndLeavesExistingOutputUntouched() throws Exception {
    Path input = sample(0),
        signed = directory.resolve("signed.pdf"),
        output = directory.resolve("out.pdf");
    try (var document = Loader.loadPDF(input.toFile());
        var stream = Files.newOutputStream(signed)) {
      var signature = new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
      document.addSignature(signature, bytes -> new byte[] {1, 2, 3});
      document.saveIncremental(stream);
    }
    Files.writeString(output, "existing");
    assertThrows(
        java.io.IOException.class,
        () ->
            PdfWatermarkUtils.addText(
                signed, null, output, new TextWatermark("1", "Draft", 30, 40, 20, 0, 0, 1)));
    assertEquals("existing", Files.readString(output));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfWatermarkUtils.addText(
                input, null, input, new TextWatermark("1", "Draft", 30, 40, 20, 0, 0, 1)));
  }
}
