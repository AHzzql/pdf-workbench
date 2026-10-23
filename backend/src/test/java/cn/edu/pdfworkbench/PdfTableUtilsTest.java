package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.Rect;
import cn.edu.pdfworkbench.model.TableData;
import cn.edu.pdfworkbench.model.TableOptions;
import cn.edu.pdfworkbench.util.PdfTableUtils;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTableUtilsTest {
  @TempDir Path directory;

  private Path sample(boolean grid) throws Exception {
    Path file = directory.resolve(grid ? "table.pdf" : "borderless.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        if (grid) {
          for (int x = 60; x <= 360; x += 100) {
            stream.moveTo(x, 500);
            stream.lineTo(x, 660);
          }
          for (int y = 500; y <= 660; y += 40) {
            stream.moveTo(60, y);
            stream.lineTo(360, y);
          }
          stream.stroke();
        }
        PdfTextUtilsTest.text(stream, "Name", 65, 640, 12, Color.BLACK);
        PdfTextUtilsTest.text(stream, "Value", 165, 640, 12, Color.BLACK);
        PdfTextUtilsTest.text(stream, "Notes", 265, 640, 12, Color.BLACK);
        PdfTextUtilsTest.text(stream, "A", 65, 600, 12, Color.BLACK);
        PdfTextUtilsTest.text(stream, "two words", 265, 600, 12, Color.BLACK);
        PdfTextUtilsTest.text(stream, "B", 65, 560, 12, Color.BLACK);
      }
      doc.save(file.toFile());
    }
    return file;
  }

  @Test
  void extractsGridAndKeepsEmptyCells() throws Exception {
    var table = PdfTableUtils.extract(sample(true), null, new TableOptions(null, null)).getFirst();
    assertEquals(4, table.rows().size());
    assertEquals(List.of("Name", "Value", "Notes"), table.rows().getFirst());
    assertEquals(List.of("A", "", "two words"), table.rows().get(1));
    assertEquals(List.of("", "", ""), table.rows().getLast());
    assertEquals(60, table.bounds().x(), 0.1);
    assertEquals(1, table.page());
  }

  @Test
  void rejectsInvalidSelectionsAndDoesNotInferBorderlessTables() throws Exception {
    assertTrue(PdfTableUtils.extract(sample(false), null, new TableOptions(null, null)).isEmpty());
    Path file = sample(true);
    assertTrue(
        PdfTableUtils.extract(file, null, new TableOptions(null, new Rect(0, 0, 10, 10)))
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTableUtils.extract(file, null, new TableOptions("2", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfTableUtils.extract(file, null, new TableOptions(null, new Rect(0, 0, -1, 10))));
  }

  @Test
  void writesQuotedCsvWithFormulaProtection() throws Exception {
    var table =
        new TableData(
            1, new Rect(0, 0, 10, 10), List.of(List.of("a,b", "say \"hi\"", "line\nnext", "=1+1")));
    Path output = directory.resolve("table.csv");
    PdfTableUtils.writeCsv(table, output);
    assertEquals(
        "\uFEFF\"a,b\",\"say \"\"hi\"\"\",\"line\nnext\",\"'=1+1\"\r\n", Files.readString(output));
    assertThrows(IllegalArgumentException.class, () -> PdfTableUtils.writeCsv(null, output));
    assertThrows(java.io.IOException.class, () -> PdfTableUtils.writeCsv(table, directory));
  }

  @Test
  void extractsChineseMultilineCellsFromRectangleBorders() throws Exception {
    Path file = directory.resolve("chinese-grid.pdf");
    try (var doc = new PDDocument();
        var fontStream = getClass().getResourceAsStream("/fonts/NotoSansSC.ttf")) {
      var font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, fontStream);
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        for (int x : new int[] {60, 160})
          for (int y : new int[] {560, 600}) stream.addRect(x, y, 100, 40);
        stream.stroke();
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(65, 625);
        stream.showText("中文");
        stream.newLineAtOffset(0, -16);
        stream.showText("第二行");
        stream.endText();
      }
      doc.save(file.toFile());
    }
    var table = PdfTableUtils.extract(file, null, new TableOptions(null, null)).getFirst();
    assertEquals(List.of("中文\n第二行", ""), table.rows().getFirst());
    assertEquals(2, table.rows().size());
  }

  @Test
  void separatesDisconnectedTables() throws Exception {
    Path file = directory.resolve("multiple.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        for (int bottom : new int[] {300, 500}) {
          for (int x : new int[] {60, 160, 260}) {
            stream.moveTo(x, bottom);
            stream.lineTo(x, bottom + 80);
          }
          for (int y : new int[] {bottom, bottom + 40, bottom + 80}) {
            stream.moveTo(60, y);
            stream.lineTo(260, y);
          }
        }
        stream.stroke();
      }
      doc.save(file.toFile());
    }
    var tables = PdfTableUtils.extract(file, null, new TableOptions(null, null));
    assertEquals(2, tables.size());
    assertEquals(500, tables.getFirst().bounds().y());
    assertEquals(
        1,
        PdfTableUtils.extract(file, null, new TableOptions(null, new Rect(50, 490, 220, 100)))
            .size());
  }

  @Test
  void handlesEmptyPagesAndMalformedFiles() throws Exception {
    Path file = directory.resolve("empty.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(file.toFile());
    }
    assertTrue(PdfTableUtils.extract(file, null, new TableOptions(null, null)).isEmpty());
    Path bad = Files.writeString(directory.resolve("bad.pdf"), "bad");
    assertThrows(
        java.io.IOException.class,
        () -> PdfTableUtils.extract(bad, null, new TableOptions(null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TableData(1, new Rect(0, 0, 1, 1), List.of(List.of("a"), List.of("a", "b"))));
  }

  @Test
  void decorativeRectangleIsNotATable() throws Exception {
    Path input = directory.resolve("decorative.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage(); doc.addPage(page);
      try (var stream = new PDPageContentStream(doc,page)) {
        stream.addRect(50,50,200,200);stream.stroke();
      }
      doc.save(input.toFile());
    }
    assertTrue(PdfTableUtils.extract(input,null,new TableOptions(null,null)).isEmpty());
  }
}
