package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.SignatureResult;
import cn.edu.pdfworkbench.model.SigningOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/** PDF 分离式 CMS 签名及验证。信任锚由调用方提供，不执行网络吊销查询。 */
public final class PdfSignatureUtils {
  private static final long MAX_BYTES = 200L * 1024 * 1024;

  private PdfSignatureUtils() {}

  /** 以增量修订追加 RSA/SHA-256 签名，源文件保持不变。 */
  public static void sign(Path input, String password, Path output, SigningOptions options)
      throws IOException {
    if (options == null) throw new IllegalArgumentException("签名参数不能为空");
    PdfFiles.validateOutput(input, output);
    var material = SigningMaterial.load(options);
    try (var document = PdfFiles.open(input, password);
        var signatureOptions = new SignatureOptions()) {
      if (Files.size(input) > MAX_BYTES) throw new IllegalArgumentException("文档超过签名大小限制");
      if (!document.getCurrentAccessPermission().canFillInForm())
        throw new PdfProcessingException("PDF_PERMISSION_DENIED", "文档不允许填写签名字段");
      rejectLockedDocument(document);
      var signature = new PDSignature();
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
      signature.setName(material.chain().getFirst().getSubjectX500Principal().getName());
      signature.setReason(options.reason());
      signature.setLocation(options.location());
      signature.setSignDate(Calendar.getInstance());
      signatureOptions.setPreferredSignatureSize(32768);
      document.addSignature(
          signature,
          content -> {
            try {
              var generator = new CMSSignedDataGenerator();
              var signer =
                  new JcaContentSignerBuilder("SHA256withRSA").build(material.privateKey());
              generator.addSignerInfoGenerator(
                  new JcaSignerInfoGeneratorBuilder(
                          new JcaDigestCalculatorProviderBuilder().build())
                      .build(signer, material.chain().getFirst()));
              generator.addCertificates(new JcaCertStore(material.chain()));
              return generator
                  .generate(new CMSProcessableByteArray(content.readAllBytes()), false)
                  .getEncoded();
            } catch (org.bouncycastle.operator.OperatorCreationException
                | java.security.cert.CertificateEncodingException
                | org.bouncycastle.cms.CMSException exception) {
              throw new IOException("无法生成 CMS 签名", exception);
            }
          },
          signatureOptions);
      Path temporary =
          Files.createTempFile(output.toAbsolutePath().getParent(), "signature-", ".tmp");
      try {
        try (var stream = Files.newOutputStream(temporary)) {
          document.saveIncremental(stream);
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /** 分别返回签名完整性、后续修订、证书有效期和信任链判定。 */
  public static List<SignatureResult> verify(
      Path input, String password, List<X509Certificate> trustAnchors) throws IOException {
    if (trustAnchors == null || trustAnchors.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("信任证书列表非法");
    try (var document = PdfFiles.open(input, password)) {
      if (Files.size(input) > MAX_BYTES) throw new IllegalArgumentException("文档超过验签大小限制");
      byte[] bytes = Files.readAllBytes(input);
      List<SignatureResult> results = new ArrayList<>();
      for (var signature : document.getSignatureDictionaries())
        results.add(SignatureVerifier.verify(signature, bytes, List.copyOf(trustAnchors)));
      return List.copyOf(results);
    }
  }

  /** 完整重写副本，移除签名字段、对应外观及签名权限字典，保留普通表单和批注。 */
  public static int remove(Path input, String password, Path output) throws IOException {
    PdfFiles.validateOutput(input, output);
    try (var document = PdfFiles.open(input, password)) {
      if (!document.getCurrentAccessPermission().canModify())
        throw new PdfProcessingException("PDF_PERMISSION_DENIED", "文档不允许修改");
      // 完整重写无法仅凭一个打开密码重建原有双密码策略，不能静默解除加密。
      if (document.isEncrypted())
        throw new PdfProcessingException("PDF_UNSUPPORTED", "移除签名需要未加密的文档副本");
      int count = document.getSignatureDictionaries().size();
      var form = document.getDocumentCatalog().getAcroForm(null);
      Set<COSDictionary> widgets = Collections.newSetFromMap(new IdentityHashMap<>());
      if (form != null) {
        if (form.hasXFA()) throw new PdfProcessingException("PDF_UNSUPPORTED", "不支持修改 XFA 签名表单");
        form.setFields(withoutSignatures(form.getFields(), widgets, 0));
        form.getCOSObject().removeItem(COSName.SIG_FLAGS);
      }
      for (var page : document.getPages()) {
        var annotations = new ArrayList<>(page.getAnnotations());
        annotations.removeIf(annotation -> widgets.contains(annotation.getCOSObject()));
        page.setAnnotations(annotations);
      }
      var permissions =
          document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS);
      if (permissions != null) {
        permissions.removeItem(COSName.DOCMDP);
        permissions.removeItem(COSName.getPDFName("UR"));
        permissions.removeItem(COSName.getPDFName("UR3"));
        if (permissions.size() == 0)
          document.getDocumentCatalog().getCOSObject().removeItem(COSName.PERMS);
      }
      PdfFiles.save(document, output);
      return count;
    }
  }

  private static List<PDField> withoutSignatures(
      List<PDField> fields, Set<COSDictionary> widgets, int depth) throws IOException {
    if (depth > 64) throw new PdfProcessingException("PDF_STRUCTURE_INVALID", "表单层级超限");
    List<PDField> retained = new ArrayList<>();
    for (var field : fields) {
      if (field instanceof PDSignatureField) {
        widgets.add(field.getCOSObject());
        for (var widget : field.getWidgets()) widgets.add(widget.getCOSObject());
      } else {
        if (field instanceof PDNonTerminalField parent)
          parent.setChildren(withoutSignatures(parent.getChildren(), widgets, depth + 1));
        retained.add(field);
      }
    }
    return retained;
  }

  private static void rejectLockedDocument(PDDocument document) throws IOException {
    var form = document.getDocumentCatalog().getAcroForm(null);
    if (form != null) {
      if (form.hasXFA()) throw new PdfProcessingException("PDF_UNSUPPORTED", "不支持 XFA 签名表单");
      for (var field : form.getFieldTree())
        if (field.getCOSObject().containsKey(COSName.getPDFName("Lock")))
          throw new PdfProcessingException("PDF_PERMISSION_DENIED", "文档包含字段锁定策略");
    }
    for (var signature : document.getSignatureDictionaries())
      if (signature.getCOSObject().containsKey(COSName.REFERENCE))
        throw new PdfProcessingException("PDF_PERMISSION_DENIED", "文档包含认证或字段变换策略");
  }
}
