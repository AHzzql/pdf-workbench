package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.util.PdfInfoUtils;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfInfoUtilsTest {
  @TempDir Path directory;

  @Test
  void extractsChineseMetadataNestedBookmarksAndAnnotations() throws Exception {
    Path file = directory.resolve("info.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      doc.getDocumentInformation().setAuthor("作者");
      doc.getDocumentInformation().setTitle("报告");
      doc.getDocumentInformation().setCustomMetadataValue("Project", "课程");
      var outline = new PDDocumentOutline();
      var parent = new PDOutlineItem();
      parent.setTitle("第一章");
      parent.setDestination(page);
      var child = new PDOutlineItem();
      child.setTitle("子项");
      child.setDestination(page);
      parent.addLast(child);
      outline.addLast(parent);
      doc.getDocumentCatalog().setDocumentOutline(outline);
      var note = new PDAnnotationText();
      note.setContents("批注内容");
      note.setTitlePopup("作者");
      note.setRectangle(new PDRectangle(10, 20, 30, 40));
      var highlight = new PDAnnotationHighlight();
      highlight.setContents("重点");
      highlight.setRectangle(new PDRectangle(60, 80, 100, 20));
      highlight.setQuadPoints(new float[] {60, 100, 160, 100, 60, 80, 160, 80});
      page.getAnnotations().add(note);
      page.getAnnotations().add(highlight);
      doc.save(file.toFile());
    }
    var info = PdfInfoUtils.read(file, null);
    assertEquals("作者", info.standardMetadata().get("Author"));
    assertEquals("课程", info.customMetadata().get("Project"));
    assertEquals(1, info.bookmarks().getFirst().page());
    assertEquals("子项", info.bookmarks().getFirst().children().getFirst().title());
    assertEquals(2, info.annotations().size());
    assertEquals("批注内容", info.annotations().getFirst().contents());
    assertEquals(8, info.annotations().get(1).quadPoints().size());
  }

  @Test
  void handlesMissingInformationAndBadInput() throws Exception {
    Path file = directory.resolve("empty.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(file.toFile());
    }
    var info = PdfInfoUtils.read(file, null);
    assertTrue(info.bookmarks().isEmpty());
    assertTrue(info.annotations().isEmpty());
    assertNull(info.standardMetadata().get("Author"));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfInfoUtils.read(directory.resolve("missing.pdf"), null));
  }

  @Test
  void rejectsExternalEntitiesAndTerminatesBookmarkCycles() throws Exception {
    Path file = directory.resolve("untrusted.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      var metadata = new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
      metadata.importXMPMetadata(
          ("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///unreadable'>]><x>&e;</x>")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      doc.getDocumentCatalog().setMetadata(metadata);
      var outline = new PDDocumentOutline();
      var item = new PDOutlineItem();
      item.setTitle("cycle");
      outline.addLast(item);
      item.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.NEXT, item.getCOSObject());
      doc.getDocumentCatalog().setDocumentOutline(outline);
      doc.save(file.toFile());
    }
    var info =
        assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(5), () -> PdfInfoUtils.read(file, null));
    assertNull(info.xmp());
    assertEquals(1, info.bookmarks().size());
    assertTrue(info.diagnostics().stream().anyMatch(d -> d.contains("循环")));
    assertTrue(info.diagnostics().stream().anyMatch(d -> d.contains("XMP")));
  }
}
