package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.SearchOptions;
import cn.edu.pdfworkbench.model.TextOptions;
import cn.edu.pdfworkbench.util.PdfTextUtils;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTextUtilsTest {
  @TempDir Path directory;

  private Path sample() throws Exception {
    Path file = directory.resolve("text.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      page.setCropBox(new PDRectangle(20, 30, 500, 700));
      page.setRotation(90);
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        text(stream, "Bottom", 70, 600, 12, Color.BLUE);
        text(stream, "Alpha report", 70, 700, 18, Color.RED);
      }
      doc.addPage(new PDPage());
      doc.save(file.toFile());
    }
    return file;
  }

  static void text(
      PDPageContentStream stream, String text, float x, float y, float size, Color color)
      throws Exception {
    stream.beginText();
    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
    stream.setNonStrokingColor(color);
    stream.newLineAtOffset(x, y);
    stream.showText(text);
    stream.endText();
  }

  @Test
  void extractsPagesStylesAndDefaultUserCoordinates() throws Exception {
    var result = PdfTextUtils.extract(sample(), null, new TextOptions(null, true));
    assertEquals(2, result.pages().size());
    var page = result.pages().getFirst();
    assertEquals("Alpha report\n\nBottom", page.text());
    assertEquals(90, page.rotation());
    assertEquals(20, page.cropBox().x());
    var first = page.spans().getFirst();
    assertEquals(70, first.bounds().x(), 0.1);
    assertEquals(700, first.baselineY(), 0.1);
    assertEquals(18, first.fontSize(), 0.1);
    assertEquals("#FF0000", first.fillColor().rgb());
    assertTrue(first.font().contains("Helvetica"));
    assertEquals("", result.pages().get(1).text());
    assertEquals(2, page.paragraphs().size());
  }

  @Test
  void searchesWithOffsetsAndRejectsInvalidPatterns() throws Exception {
    Path file = sample();
    var matches = PdfTextUtils.search(file, null, new SearchOptions("1", "report", 0));
    assertEquals(1, matches.size());
    assertEquals(6, matches.getFirst().start());
    assertEquals("report", matches.getFirst().text());
    assertFalse(matches.getFirst().bounds().isEmpty());
    assertTrue(PdfTextUtils.search(file, null, new SearchOptions(null, "absent", 0)).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTextUtils.search(file, null, new SearchOptions(null, "[", 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTextUtils.search(file, null, new SearchOptions(null, "", 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTextUtils.search(file, null, new SearchOptions(null, "(?<=a)b", 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTextUtils.search(file, null, new SearchOptions(null, "a", 1024)));
    assertEquals(
        1,
        PdfTextUtils.search(
                file,
                null,
                new SearchOptions("1", "ALPHA", com.google.re2j.Pattern.CASE_INSENSITIVE))
            .size());
    assertEquals(2, PdfTextUtils.search(file, null, new SearchOptions(null, "^", 0)).size());
  }

  @Test
  void validatesInputAndReleasesSource() throws Exception {
    Path file = sample();
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTextUtils.extract(file, null, new TextOptions("3", true)));
    PdfTextUtils.extract(file, null, new TextOptions("2", true));
    Files.delete(file);
    Path invalid = Files.writeString(directory.resolve("invalid.pdf"), "not a PDF");
    assertThrows(
        java.io.IOException.class,
        () -> PdfTextUtils.extract(invalid, null, new TextOptions(null, true)));
  }

  @Test
  void readsColumnsRatherThanInterleavingLines() throws Exception {
    Path file = directory.resolve("columns.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        text(stream, "Right first", 330, 700, 12, Color.BLACK);
        text(stream, "Left second", 60, 680, 12, Color.BLACK);
        text(stream, "Right second", 330, 680, 12, Color.BLACK);
        text(stream, "Left first", 60, 700, 12, Color.BLACK);
      }
      doc.save(file.toFile());
    }
    String text =
        PdfTextUtils.extract(file, null, new TextOptions(null, true)).pages().getFirst().text();
    assertTrue(text.indexOf("Left second") < text.indexOf("Right first"), text);
  }

  @Test
  void extractsChineseWithEmbeddedFontAndCrossLineMatches() throws Exception {
    Path file = directory.resolve("chinese.pdf");
    try (var doc = new PDDocument();
        var fontStream = getClass().getResourceAsStream("/fonts/NotoSansSC.ttf")) {
      assertNotNull(fontStream);
      var font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, fontStream);
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(60, 700);
        stream.showText("中文报告");
        stream.newLineAtOffset(0, -16);
        stream.showText("第二行");
        stream.endText();
      }
      doc.save(file.toFile());
    }
    var page = PdfTextUtils.extract(file, null, new TextOptions(null, true)).pages().getFirst();
    assertEquals("中文报告\n第二行", page.text());
    assertEquals(1, page.paragraphs().size());
    var match = PdfTextUtils.search(file, null, new SearchOptions(null, "报告\\n第二", 0)).getFirst();
    assertEquals(2, match.start());
    assertEquals(4, match.bounds().size());
  }

  @Test
  void verifiesPasswordAndExtractionPermission() throws Exception {
    Path file = directory.resolve("restricted.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      var permission = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
      permission.setCanExtractContent(false);
      var policy =
          new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
              "owner", "reader", permission);
      policy.setEncryptionKeyLength(128);
      policy.setPreferAES(true);
      doc.protect(policy);
      doc.save(file.toFile());
    }
    var passwordError =
        assertThrows(
            cn.edu.pdfworkbench.exception.PdfProcessingException.class,
            () -> PdfTextUtils.extract(file, "wrong", new TextOptions(null, true)));
    assertEquals("PDF_PASSWORD_INVALID", passwordError.getCode());
    var permissionError =
        assertThrows(
            cn.edu.pdfworkbench.exception.PdfProcessingException.class,
            () -> PdfTextUtils.extract(file, "reader", new TextOptions(null, true)));
    assertEquals("PDF_PERMISSION_DENIED", permissionError.getCode());
    assertEquals(
        1, PdfTextUtils.extract(file, "owner", new TextOptions(null, true)).pages().size());
    assertThrows(
        cn.edu.pdfworkbench.exception.PdfProcessingException.class,
        () -> cn.edu.pdfworkbench.util.PdfInfoUtils.read(file, "reader"));
    Files.delete(file);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 90, 180, 270})
  void preservesCoordinatesAcrossPageRotation(int rotation) throws Exception {
    Path file = directory.resolve("rotation.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      page.setRotation(rotation);
      page.setUserUnit(2);
      page.setCropBox(new PDRectangle(20, 30, 500, 700));
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        text(stream, "X", 70, 700, 12, Color.BLUE);
      }
      doc.save(file.toFile());
    }
    var page = PdfTextUtils.extract(file, null, new TextOptions(null, false)).pages().getFirst();
    assertEquals(70, page.spans().getFirst().bounds().x(), 0.1);
    assertEquals(700, page.spans().getFirst().bounds().y(), 0.1);
    assertEquals(2, page.userUnit());
    assertTrue(page.paragraphs().isEmpty());
    assertEquals("#0000FF", page.spans().getFirst().fillColor().rgb());
  }
  @Test
  void groupsContiguousStyledGlyphsIntoBlocks() throws Exception {
    Path file = directory.resolve("blocks.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        text(stream, "Alpha report", 60, 700, 12, Color.RED);
        text(stream, "Blue", 60 + new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth("Alpha report") / 1000 * 12, 700, 12, Color.BLUE);
        text(stream, "Far", 400, 700, 12, Color.BLUE);
        text(stream, "Next", 60, 680, 12, Color.RED);
      }
      doc.addPage(new PDPage());
      doc.save(file.toFile());
    }
    var result = PdfTextUtils.extract(file, null, new TextOptions(null, true));
    var page = result.pages().getFirst();
    assertEquals(List.of("Alpha report", "Blue", "Far", "Next"),
        page.blocks().stream().map(b -> b.text()).toList());
    for (var block : page.blocks()) {
      assertEquals(1, block.page());
      assertEquals(block.text(), page.text().substring(block.start(), block.end()));
      var glyphs = page.spans().stream()
          .filter(g -> g.start() >= block.start() && g.end() <= block.end()).toList();
      assertEquals(glyphs.getFirst().bounds().x(), block.bounds().x(), 0.01);
      double right = glyphs.stream().mapToDouble(g -> g.bounds().x() + g.bounds().width()).max().orElseThrow();
      assertEquals(right - block.bounds().x(), block.bounds().width(), 0.01);
      assertEquals(glyphs.getFirst().fillColor(), block.fillColor());
    }
    assertTrue(result.pages().get(1).blocks().isEmpty());
  }

}
