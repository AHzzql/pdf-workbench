package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.EncryptionOptions;
import cn.edu.pdfworkbench.util.PdfEncryptionUtils;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PdfEncryptionUtilsTest {
  @TempDir Path directory;

  private Path sample() throws Exception {
    Path input = directory.resolve("source.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        PdfTextUtilsTest.text(content, "Encryption test", 60, 700, 12, Color.BLACK);
      }
      document.save(input.toFile());
    }
    return input;
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> configurations() {
    return Stream.of(128, 256)
        .flatMap(
            bits ->
                java.util.stream.IntStream.range(0, 8)
                    .mapToObj(
                        flags -> org.junit.jupiter.params.provider.Arguments.of(bits, flags)));
  }

  @ParameterizedTest
  @MethodSource("configurations")
  void encryptsWithIndependentPermissions(int bits, int flags) throws Exception {
    Path input = sample(), output = directory.resolve("encrypted.pdf");
    byte[] original = Files.readAllBytes(input);
    boolean print = (flags & 1) != 0, copy = (flags & 2) != 0, modify = (flags & 4) != 0;
    PdfEncryptionUtils.encrypt(
        input, null, output, new EncryptionOptions("reader", "owner", bits, print, copy, modify));
    assertArrayEquals(original, Files.readAllBytes(input));
    try (var document = Loader.loadPDF(output.toFile(), "reader")) {
      assertTrue(document.isEncrypted());
      assertEquals(bits, document.getEncryption().getLength());
      assertEquals(
          bits == 128 ? "AESV2" : "AESV3",
          document.getEncryption().getStdCryptFilterDictionary().getCryptFilterMethod().getName());
      var permission = document.getCurrentAccessPermission();
      assertEquals(print, permission.canPrint());
      assertEquals(print, permission.canPrintFaithful());
      assertEquals(copy, permission.canExtractContent());
      assertEquals(modify, permission.canModify());
      assertEquals(modify, permission.canModifyAnnotations());
      assertEquals(modify, permission.canFillInForm());
      assertEquals(modify, permission.canAssembleDocument());
      assertTrue(permission.isReadOnly());
    }
    try (var document = Loader.loadPDF(output.toFile(), "owner")) {
      assertTrue(document.getCurrentAccessPermission().isOwnerPermission());
      assertFalse(document.getCurrentAccessPermission().isReadOnly());
      assertTrue(
          new org.apache.pdfbox.text.PDFTextStripper()
              .getText(document)
              .contains("Encryption test"));
    }
    assertThrows(
        org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class,
        () -> Loader.loadPDF(output.toFile(), "incorrect"));
  }

  @Test
  void rejectsInvalidParametersAndPreservesExistingFiles() throws Exception {
    Path input = sample(), output = Files.writeString(directory.resolve("output.pdf"), "existing");
    var invalid = new EncryptionOptions("reader", "owner", 64, true, true, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfEncryptionUtils.encrypt(input, null, output, invalid));
    assertEquals("existing", Files.readString(output));
    var valid = new EncryptionOptions("", "owner", 256, true, true, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfEncryptionUtils.encrypt(input, null, input, valid));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfEncryptionUtils.encrypt(
                input, null, output, new EncryptionOptions("same", "same", 128, true, true, true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfEncryptionUtils.encrypt(
                input, null, output, new EncryptionOptions("", "", 128, true, true, true)));
    assertFalse(valid.toString().contains("owner"));
  }

  @Test
  void requiresOwnerPermissionToReplaceEncryption() throws Exception {
    Path input = sample(),
        encrypted = directory.resolve("encrypted.pdf"),
        output = directory.resolve("again.pdf");
    var options = new EncryptionOptions("reader", "owner", 256, true, true, true);
    PdfEncryptionUtils.encrypt(input, null, encrypted, options);
    var error =
        assertThrows(
            PdfProcessingException.class,
            () -> PdfEncryptionUtils.encrypt(encrypted, "reader", output, options));
    assertEquals("PDF_PERMISSION_DENIED", error.getCode());
    assertThrows(
        PdfProcessingException.class,
        () -> PdfEncryptionUtils.encrypt(encrypted, "wrong", output, options));
    PdfEncryptionUtils.encrypt(
        encrypted,
        "owner",
        output,
        new EncryptionOptions("new", "new-owner", 128, false, false, false));
    try (var document = Loader.loadPDF(output.toFile(), "new")) {
      assertEquals(128, document.getEncryption().getLength());
    }
  }

  @Test
  void validatesPasswordEncodingAndSupportsEmptyUserPassword() throws Exception {
    Path input = sample(), output = directory.resolve("unicode.pdf");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfEncryptionUtils.encrypt(
                input, null, output, new EncryptionOptions("中文", "owner", 128, true, true, true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfEncryptionUtils.encrypt(
                input,
                null,
                output,
                new EncryptionOptions("a".repeat(128), "owner", 256, true, true, true)));
    PdfEncryptionUtils.encrypt(
        input, null, output, new EncryptionOptions("中文", "所有者", 256, true, true, true));
    try (var document = Loader.loadPDF(output.toFile(), "中文")) {
      assertTrue(document.isEncrypted());
    }
    Path empty = directory.resolve("empty-password.pdf");
    PdfEncryptionUtils.encrypt(
        input, null, empty, new EncryptionOptions("", "owner", 128, false, false, false));
    try (var document = Loader.loadPDF(empty.toFile())) {
      assertFalse(document.getCurrentAccessPermission().canPrint());
    }
  }

  @Test
  void refusesSignedFilesAndCleansFailedOutput() throws Exception {
    Path signed = directory.resolve("signed.pdf");
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
      document.getDocumentCatalog().setAcroForm(form);
      var field = new org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField(form);
      field.setValue(new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature());
      form.getFields().add(field);
      document.save(signed.toFile());
    }
    Path output = directory.resolve("out.pdf");
    var options = new EncryptionOptions("reader", "owner", 128, true, true, true);
    var error =
        assertThrows(
            PdfProcessingException.class,
            () -> PdfEncryptionUtils.encrypt(signed, null, output, options));
    assertEquals("SIGNED_DOCUMENT", error.getCode());
    assertFalse(Files.exists(output));
    Path input = sample();
    assertThrows(
        java.io.IOException.class,
        () ->
            PdfEncryptionUtils.encrypt(input, null, directory.resolve("missing/out.pdf"), options));
    try (var files = Files.list(directory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }
}
