package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.pdfworkbench.model.SigningOptions;
import cn.edu.pdfworkbench.util.PdfSignatureUtils;
import java.awt.Color;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSignatureUtilsTest {
  @TempDir Path directory;

  X509Certificate certificate(Path file) throws Exception {
    return certificate(file, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400));
  }

  private X509Certificate certificate(Path file, Instant start, Instant end) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    var name = new X500Name("CN=PDF Workbench Test");
    var builder =
        new JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date.from(start), Date.from(end), name, pair.getPublic());
    var cert =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry(
        "signer",
        pair.getPrivate(),
        "test-password".toCharArray(),
        new java.security.cert.Certificate[] {cert});
    try (var stream = Files.newOutputStream(file)) {
      store.store(stream, "test-password".toCharArray());
    }
    return cert;
  }

  private Path sample() throws Exception {
    Path input = directory.resolve("source.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        PdfTextUtilsTest.text(content, "Signed report", 60, 700, 12, Color.BLACK);
      }
      document.save(input.toFile());
    }
    return input;
  }

  @Test
  void expiredCertificateDoesNotChangeCryptographicIntegrity() throws Exception {
    Path key = directory.resolve("expired.p12"),
        input = sample(),
        signed = directory.resolve("expired-signed.pdf");
    var certificate =
        certificate(key, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
    var store = KeyStore.getInstance("PKCS12");
    try (var stream = Files.newInputStream(key)) {
      store.load(stream, "test-password".toCharArray());
    }
    var privateKey =
        (java.security.PrivateKey) store.getKey("signer", "test-password".toCharArray());
    try (var document = Loader.loadPDF(input.toFile());
        var output = Files.newOutputStream(signed)) {
      var signature = new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
      signature.setFilter(
          org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(
          org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
              .SUBFILTER_ADBE_PKCS7_DETACHED);
      document.addSignature(
          signature,
          content -> {
            try {
              var generator = new org.bouncycastle.cms.CMSSignedDataGenerator();
              generator.addSignerInfoGenerator(
                  new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(
                          new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder()
                              .build())
                      .build(
                          new JcaContentSignerBuilder("SHA256withRSA").build(privateKey),
                          certificate));
              generator.addCertificates(
                  new org.bouncycastle.cert.jcajce.JcaCertStore(List.of(certificate)));
              return generator
                  .generate(
                      new org.bouncycastle.cms.CMSProcessableByteArray(content.readAllBytes()),
                      false)
                  .getEncoded();
            } catch (Exception e) {
              throw new java.io.IOException(e);
            }
          });
      document.saveIncremental(output);
    }
    var result = PdfSignatureUtils.verify(signed, null, List.of()).getFirst();
    assertEquals("VALID", result.integrity());
    assertEquals("EXPIRED", result.certificateValidity());
  }

  @Test
  void buildsPkixChainToSuppliedRoot() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var rootKey = generator.generateKeyPair();
    var leafKey = generator.generateKeyPair();
    var rootName = new X500Name("CN=Test Root");
    var leafName = new X500Name("CN=Test Signer");
    var start = Date.from(Instant.now().minusSeconds(60));
    var end = Date.from(Instant.now().plusSeconds(86400));
    var rootBuilder =
        new JcaX509v3CertificateBuilder(
            rootName, BigInteger.ONE, start, end, rootName, rootKey.getPublic());
    rootBuilder.addExtension(
        org.bouncycastle.asn1.x509.Extension.basicConstraints,
        true,
        new org.bouncycastle.asn1.x509.BasicConstraints(true));
    var converter = new JcaX509CertificateConverter();
    var root =
        converter.getCertificate(
            rootBuilder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(rootKey.getPrivate())));
    var leafBuilder =
        new JcaX509v3CertificateBuilder(
            rootName, BigInteger.TWO, start, end, leafName, leafKey.getPublic());
    leafBuilder.addExtension(
        org.bouncycastle.asn1.x509.Extension.basicConstraints,
        true,
        new org.bouncycastle.asn1.x509.BasicConstraints(false));
    var leaf =
        converter.getCertificate(
            leafBuilder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(rootKey.getPrivate())));
    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    char[] password = "test-password".toCharArray();
    store.setKeyEntry(
        "signer",
        leafKey.getPrivate(),
        password,
        new java.security.cert.Certificate[] {leaf, root});
    Path key = directory.resolve("chain.p12"), signed = directory.resolve("chain.pdf");
    try (var stream = Files.newOutputStream(key)) {
      store.store(stream, password);
    }
    try (var options = new SigningOptions(key, password, null, null, null)) {
      PdfSignatureUtils.sign(sample(), null, signed, options);
    }
    var result = PdfSignatureUtils.verify(signed, null, List.of(root)).getFirst();
    assertEquals("VALID", result.integrity());
    assertEquals("TRUSTED", result.trustStatus());
  }

  @Test
  void signsVerifiesAndRemovesWithoutChangingSource() throws Exception {
    Path input = sample(),
        container = directory.resolve("test.p12"),
        signed = directory.resolve("signed.pdf");
    var cert = certificate(container);
    byte[] original = Files.readAllBytes(input);
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), null, "Review", "Local")) {
      PdfSignatureUtils.sign(input, null, signed, options);
      assertFalse(options.toString().contains("test-password"));
    }
    assertArrayEquals(original, Files.readAllBytes(input));
    var result = PdfSignatureUtils.verify(signed, null, List.of(cert)).getFirst();
    assertEquals("VALID", result.integrity());
    assertFalse(result.hasLaterRevision());
    assertEquals("VALID", result.certificateValidity());
    assertEquals("TRUSTED", result.trustStatus());
    assertFalse(result.revocationChecked());
    assertEquals(
        "NOT_EVALUATED",
        PdfSignatureUtils.verify(signed, null, List.of()).getFirst().trustStatus());
    Path removed = directory.resolve("removed.pdf");
    assertEquals(1, PdfSignatureUtils.remove(signed, null, removed));
    assertTrue(PdfSignatureUtils.verify(removed, null, List.of()).isEmpty());
    try (var document = Loader.loadPDF(removed.toFile())) {
      assertTrue(
          new org.apache.pdfbox.text.PDFTextStripper().getText(document).contains("Signed report"));
      assertTrue(document.getPage(0).getAnnotations().isEmpty());
    }
    Path evidence = Files.createDirectories(Path.of("target", "verification"));
    Files.copy(
        signed, evidence.resolve("signed.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        removed,
        evidence.resolve("removed.pdf"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  void detectsTamperingAndDistinguishesLaterRevisions() throws Exception {
    Path input = sample(),
        container = directory.resolve("key.p12"),
        signed = directory.resolve("signed.pdf");
    var cert = certificate(container);
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), null, null, null)) {
      PdfSignatureUtils.sign(input, null, signed, options);
    }
    byte[] bytes = Files.readAllBytes(signed);
    bytes[7] = bytes[7] == '7' ? (byte) '6' : (byte) '7';
    Path tampered = Files.write(directory.resolve("tampered.pdf"), bytes);
    assertEquals(
        "INVALID", PdfSignatureUtils.verify(tampered, null, List.of(cert)).getFirst().integrity());
    Path revised = directory.resolve("revised.pdf");
    try (var document = Loader.loadPDF(signed.toFile());
        var output = Files.newOutputStream(revised)) {
      document.getDocumentInformation().setCustomMetadataValue("Revision", "later");
      document.saveIncremental(output);
    }
    var result = PdfSignatureUtils.verify(revised, null, List.of(cert)).getFirst();
    assertEquals("VALID", result.integrity());
    assertTrue(result.hasLaterRevision());
  }

  @Test
  void rejectsBadCertificatePasswordsAndAcceptsUnsignedBoundaries() throws Exception {
    Path input = sample(),
        container = directory.resolve("key.p12"),
        output = directory.resolve("out.pdf");
    certificate(container);
    try (var options = new SigningOptions(container, "wrong".toCharArray(), null, null, null)) {
      assertThrows(
          java.io.IOException.class, () -> PdfSignatureUtils.sign(input, null, output, options));
    }
    assertFalse(Files.exists(output));
    assertTrue(PdfSignatureUtils.verify(input, null, List.of()).isEmpty());
    assertEquals(0, PdfSignatureUtils.remove(input, null, output));
    assertThrows(
        IllegalArgumentException.class, () -> PdfSignatureUtils.remove(input, null, input));
    assertThrows(
        IllegalArgumentException.class,
        () -> PdfSignatureUtils.verify(directory.resolve("missing"), null, List.of()));
  }

  @Test
  void retainsOrdinaryFieldsAndAnnotationsWhenRemovingMultipleSignatures() throws Exception {
    Path input = sample(),
        formFile = directory.resolve("form.pdf"),
        container = directory.resolve("key.p12");
    var cert = certificate(container);
    try (var document = Loader.loadPDF(input.toFile())) {
      var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
      document.getDocumentCatalog().setAcroForm(form);
      var field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
      field.setPartialName("reference");
      form.setFields(List.of(field));
      var note = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText();
      note.setContents("Retained note");
      document.getPage(0).getAnnotations().add(note);
      document.save(formFile.toFile());
    }
    Path first = directory.resolve("first.pdf"),
        second = directory.resolve("second.pdf"),
        removed = directory.resolve("clean.pdf");
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), "signer", null, null)) {
      PdfSignatureUtils.sign(formFile, null, first, options);
      PdfSignatureUtils.sign(first, null, second, options);
    }
    var results = PdfSignatureUtils.verify(second, null, List.of(cert));
    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(result -> "VALID".equals(result.integrity())));
    assertTrue(results.getFirst().hasLaterRevision());
    assertFalse(results.getLast().hasLaterRevision());
    assertEquals(2, PdfSignatureUtils.remove(second, null, removed));
    try (var document = Loader.loadPDF(removed.toFile())) {
      var form = document.getDocumentCatalog().getAcroForm(null);
      assertEquals(1, form.getFields().size());
      assertEquals("reference", form.getFields().getFirst().getPartialName());
      assertFalse(form.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.SIG_FLAGS));
      assertEquals(1, document.getPage(0).getAnnotations().size());
      assertEquals("Retained note", document.getPage(0).getAnnotations().getFirst().getContents());
      assertFalse(
          document
              .getDocumentCatalog()
              .getCOSObject()
              .containsKey(org.apache.pdfbox.cos.COSName.PERMS));
    }
  }

  @Test
  void rejectsMalformedRangesAndDoesNotTrustUnrelatedCertificates() throws Exception {
    Path input = sample(),
        container = directory.resolve("key.p12"),
        signed = directory.resolve("signed.pdf");
    certificate(container);
    var unrelated = certificate(directory.resolve("other.p12"));
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), null, null, null)) {
      PdfSignatureUtils.sign(input, null, signed, options);
    }
    var result = PdfSignatureUtils.verify(signed, null, List.of(unrelated)).getFirst();
    assertEquals("VALID", result.integrity());
    assertEquals("UNTRUSTED", result.trustStatus());
    byte[] bytes = Files.readAllBytes(signed);
    byte[] marker = "/ByteRange [0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    String contents = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    int index = contents.indexOf(new String(marker, java.nio.charset.StandardCharsets.US_ASCII));
    assertTrue(index >= 0);
    bytes[index + marker.length - 1] = '1';
    Path malformed = Files.write(directory.resolve("range.pdf"), bytes);
    assertEquals(
        "INVALID", PdfSignatureUtils.verify(malformed, null, List.of()).getFirst().integrity());
  }

  @Test
  void rejectsExpiredCertificatesAndUnknownAliasesWithoutReplacingOutput() throws Exception {
    Path input = sample(),
        container = directory.resolve("expired.p12"),
        output = directory.resolve("output.pdf");
    certificate(container, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
    byte[] previous = "previous output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(output, previous);
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), null, null, null)) {
      assertThrows(
          java.io.IOException.class, () -> PdfSignatureUtils.sign(input, null, output, options));
    }
    certificate(container);
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), "missing", null, null)) {
      assertThrows(
          java.io.IOException.class, () -> PdfSignatureUtils.sign(input, null, output, options));
    }
    assertArrayEquals(previous, Files.readAllBytes(output));
  }

  @Test
  void enforcesEncryptedDocumentPermissions() throws Exception {
    Path input = sample(),
        encrypted = directory.resolve("encrypted.pdf"),
        container = directory.resolve("key.p12"),
        signed = directory.resolve("signed.pdf");
    certificate(container);
    cn.edu.pdfworkbench.util.PdfEncryptionUtils.encrypt(
        input,
        null,
        encrypted,
        new cn.edu.pdfworkbench.model.EncryptionOptions("reader", "owner", 256, true, true, false));
    try (var options =
        new SigningOptions(container, "test-password".toCharArray(), null, null, null)) {
      assertThrows(
          java.io.IOException.class,
          () -> PdfSignatureUtils.sign(encrypted, "reader", signed, options));
      PdfSignatureUtils.sign(encrypted, "owner", signed, options);
    }
    assertEquals(
        "VALID", PdfSignatureUtils.verify(signed, "reader", List.of()).getFirst().integrity());
    Path removed = directory.resolve("removed.pdf");
    assertThrows(
        java.io.IOException.class, () -> PdfSignatureUtils.remove(signed, "reader", removed));
    var error =
        assertThrows(
            cn.edu.pdfworkbench.exception.PdfProcessingException.class,
            () -> PdfSignatureUtils.remove(signed, "owner", removed));
    assertTrue(error.getMessage().contains("未加密"));
    assertFalse(Files.exists(removed));
  }
  @Test
  void distinguishesUnsupportedAndMissingCertificateFromInvalidSignatures() throws Exception {
    Path key = directory.resolve("unknown.p12"), signed = directory.resolve("supported.pdf");
    var cert = certificate(key);
    try (var options = new SigningOptions(key, "test-password".toCharArray(), null, null, null)) {
      PdfSignatureUtils.sign(sample(), null, signed, options);
    }
    var normal = PdfSignatureUtils.verify(signed, null, List.of()).getFirst();
    assertEquals(cert.getSubjectX500Principal().getName(), normal.certificate().subject());
    assertEquals(cert.getSerialNumber().toString(), normal.certificate().serialNumber());
    assertEquals(cert.getNotBefore().toInstant(), normal.certificate().notBefore());
    byte[] bytes = Files.readAllBytes(signed);
    String token = "/adbe.pkcs7.detached";
    int position = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf(token);
    assertTrue(position > 0);
    byte[] changed = bytes.clone();
    byte[] replacement = ("/unknown" + " ".repeat(token.length() - 8)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(replacement, 0, changed, position, replacement.length);
    Path unsupported = Files.write(directory.resolve("unsupported.pdf"), changed);
    Files.copy(unsupported, Path.of("target", "verification", "unsupported-signature.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    var unknown = PdfSignatureUtils.verify(unsupported, null, List.of()).getFirst();
    assertEquals("UNKNOWN", unknown.integrity());
    assertEquals(Boolean.FALSE, unknown.hasLaterRevision());
    assertNotNull(unknown.reason());
    try (var document = Loader.loadPDF(bytes)) {
      var signature = document.getSignatureDictionaries().getFirst();
      var cms = new org.bouncycastle.cms.CMSSignedData(signature.getContents(bytes));
      var parsed = org.bouncycastle.asn1.cms.SignedData.getInstance(cms.toASN1Structure().getContent());
      var original = org.bouncycastle.asn1.cms.SignerInfo.getInstance(parsed.getSignerInfos().getObjectAt(0));
      var altered = new org.bouncycastle.asn1.cms.SignerInfo(original.getSID(), original.getDigestAlgorithm(),
          original.getAuthenticatedAttributes(), new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
              new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.5.6.7")), original.getEncryptedDigest(), original.getUnauthenticatedAttributes());
      var alteredData = new org.bouncycastle.asn1.cms.SignedData(parsed.getDigestAlgorithms(),
          parsed.getEncapContentInfo(), parsed.getCertificates(), parsed.getCRLs(), new org.bouncycastle.asn1.DERSet(altered));
      var encoded = new org.bouncycastle.asn1.cms.ContentInfo(org.bouncycastle.asn1.cms.CMSObjectIdentifiers.signedData, alteredData).getEncoded();
      Path algorithm = Files.write(directory.resolve("unsupported-algorithm.pdf"), replaceCms(bytes, signature.getByteRange(), encoded));
      var algorithmResult = PdfSignatureUtils.verify(algorithm, null, List.of()).getFirst();
      assertEquals("UNKNOWN", algorithmResult.integrity());
      assertNotNull(algorithmResult.certificate());
      var without = org.bouncycastle.cms.CMSSignedData.replaceCertificatesAndCRLs(cms, null, null, null);
      bytes = replaceCms(bytes, signature.getByteRange(), without.getEncoded());
    }
    Path missing = Files.write(directory.resolve("missing-certificate.pdf"), bytes);
    var result = PdfSignatureUtils.verify(missing, null, List.of()).getFirst();
    assertEquals("UNKNOWN", result.integrity());
    assertNull(result.certificate());
    assertNotNull(result.algorithm());
    assertEquals("NOT_EVALUATED", result.trustStatus());
    assertEquals(Boolean.FALSE, result.hasLaterRevision());
  }

  @Test
  void malformedByteRangeDoesNotReportAnUnmodifiedRevision() throws Exception {
    Path file = directory.resolve("malformed-range.pdf");
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
      document.getDocumentCatalog().setAcroForm(form);
      var field = new org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField(form);
      field.setValue(new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature());
      form.getFields().add(field);
      document.save(file.toFile());
    }
    Files.copy(file, Path.of("target", "verification", "malformed-signature.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    var result = PdfSignatureUtils.verify(file, null, List.of()).getFirst();
    assertEquals("INVALID", result.integrity());
    assertNull(result.hasLaterRevision());
  }

  private static byte[] replaceCms(byte[] source, int[] range, byte[] cms) {
    byte[] bytes = source.clone();
    java.util.Arrays.fill(bytes, range[1] + 1, range[2] - 1, (byte) '0');
    var hex = org.bouncycastle.util.encoders.Hex.toHexString(cms).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    assertTrue(hex.length <= range[2] - range[1] - 2);
    System.arraycopy(hex, 0, bytes, range[1] + 1, hex.length);
    return bytes;
  }

}
