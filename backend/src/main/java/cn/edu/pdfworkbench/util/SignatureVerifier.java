package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.model.SignatureResult;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;

final class SignatureVerifier {
  private SignatureVerifier() {}

  static SignatureResult verify(PDSignature signature, byte[] file, List<X509Certificate> anchors) {
    String signer = null, algorithm = null, validity = "UNKNOWN", trust = "NOT_EVALUATED";
    Boolean later = null;
    SignatureResult.CertificateSummary summary = null;
    try {
      int[] range = signature.getByteRange();
      if (range == null || range.length != 4 || range[0] != 0 || range[1] <= 0
          || range[2] <= range[1] || range[3] < 0 || (long) range[2] + range[3] > file.length) {
        return unverified(signature, "INVALID", null, "签名字节范围非法");
      }
      if (file[range[1]] != '<' || file[range[2] - 1] != '>')
        return unverified(signature, "INVALID", null, "签名范围缺口不是 Contents");
      // 将范围缺口与当前签名字典的 Contents 对齐，防止验证另一段无关 CMS。
      String hex = new String(file, range[1] + 1, range[2] - range[1] - 2,
          java.nio.charset.StandardCharsets.US_ASCII).replaceAll("\\s", "");
      byte[] gap = org.bouncycastle.util.encoders.Hex.decode(hex);
      if (!java.util.Arrays.equals(gap, signature.getContents()))
        return unverified(signature, "INVALID", null, "签名范围与 Contents 不一致");
      later = (long) range[2] + range[3] < file.length;
      if (!"adbe.pkcs7.detached".equals(signature.getSubFilter())
          && !"ETSI.CAdES.detached".equals(signature.getSubFilter()))
        return unverified(signature, "UNKNOWN", later, "签名类型不受支持");
      var cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(file)),
          signature.getContents(file));
      if (!cms.isDetachedSignature())
        return unverified(signature, "INVALID", later, "CMS 内容不是分离式签名");
      var signers = cms.getSignerInfos().getSigners();
      if (signers.isEmpty()) return unverified(signature, "INVALID", later, "CMS 缺少签署者");
      if (signers.size() != 1)
        return unverified(signature, "UNKNOWN", later, "CMS 签署者数量不受支持");
      var info = signers.iterator().next();
      algorithm = info.getDigestAlgOID() + " / " + info.getEncryptionAlgOID();
      var matches = cms.getCertificates().getMatches(null).stream()
          .filter(holder -> info.getSID().match(holder)).toList();
      if (matches.size() != 1)
        return new SignatureResult(signature.getName(), null, algorithm, "UNKNOWN", later,
            "UNKNOWN", "NOT_EVALUATED", false, null, "无法确定签署证书");
      var converter = new JcaX509CertificateConverter();
      X509Certificate certificate = converter.getCertificate(matches.getFirst());
      signer = certificate.getSubjectX500Principal().getName();
      summary = new SignatureResult.CertificateSummary(signer,
          certificate.getIssuerX500Principal().getName(), certificate.getSerialNumber().toString(),
          certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant());
      validity = validity(certificate);
      if (!anchors.isEmpty()) {
        List<X509Certificate> chain = new ArrayList<>();
        for (var holder : cms.getCertificates().getMatches(null))
          chain.add(converter.getCertificate(holder));
        trust = trusted(certificate, chain, anchors) ? "TRUSTED" : "UNTRUSTED";
      }
      var verifier = new JcaSimpleSignerInfoVerifierBuilder().build(certificate.getPublicKey());
      try {
        verifier.getContentVerifier(info.toASN1Structure().getDigestEncryptionAlgorithm(), info.getDigestAlgorithmID());
      } catch (IllegalArgumentException exception) {
        return new SignatureResult(signature.getName(), signer, algorithm, "UNKNOWN", later,
            validity, trust, false, summary, "签名算法或参数不受支持");
      }
      boolean valid = info.verify(verifier);
      return new SignatureResult(signature.getName(), signer, algorithm, valid ? "VALID" : "INVALID",
          later, validity, trust, false, summary, valid ? null : "CMS 摘要或签名不匹配");
    } catch (GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException exception) {
      return new SignatureResult(signature.getName(), signer, algorithm, "UNKNOWN", later,
          validity, trust, false, summary, "证书或密码学算法无法验证");
    } catch (IOException | org.bouncycastle.cms.CMSException | IllegalArgumentException
        | ClassCastException | IndexOutOfBoundsException exception) {
      boolean unsupported = unsupportedAlgorithm(exception);
      return new SignatureResult(signature.getName(), signer, algorithm,
          unsupported ? "UNKNOWN" : "INVALID", later, validity, trust, false, summary,
          unsupported ? "签名算法不受支持" : "签名结构或密码学验证失败");
    }
  }

  private static boolean unsupportedAlgorithm(Throwable failure) {
    for (int i = 0; failure != null && i < 16; i++, failure = failure.getCause())
      if (failure instanceof java.security.NoSuchAlgorithmException
          || failure instanceof java.security.NoSuchProviderException
          || failure instanceof org.bouncycastle.operator.OperatorCreationException) return true;
    return false;
  }

  private static SignatureResult unverified(
      PDSignature signature, String integrity, Boolean later, String reason) {
    return new SignatureResult(signature.getName(), null, null, integrity, later,
        "UNKNOWN", "NOT_EVALUATED", false, null, reason);
  }

  private static String validity(X509Certificate certificate) {
    try {
      certificate.checkValidity();
      return "VALID";
    } catch (CertificateExpiredException exception) {
      return "EXPIRED";
    } catch (CertificateNotYetValidException exception) {
      return "NOT_YET_VALID";
    }
  }

  private static boolean trusted(
      X509Certificate leaf, List<X509Certificate> chain, List<X509Certificate> anchors) {
    try {
      var roots = new HashSet<TrustAnchor>();
      for (var anchor : anchors) {
        anchor.checkValidity();
        roots.add(new TrustAnchor(anchor, null));
      }
      leaf.checkValidity();
      if (anchors.contains(leaf)) return true;
      var target = new X509CertSelector();
      target.setCertificate(leaf);
      var parameters = new PKIXBuilderParameters(roots, target);
      parameters.setRevocationEnabled(false);
      parameters.addCertStore(
          CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
      CertPathBuilder.getInstance("PKIX").build(parameters);
      return true;
    } catch (GeneralSecurityException exception) {
      return false;
    }
  }
}
