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
    String signer = null, algorithm = null, integrity = "INVALID", validity = "UNKNOWN";
    String trust = anchors.isEmpty() ? "NOT_EVALUATED" : "UNTRUSTED";
    boolean later = false;
    try {
      int[] range = signature.getByteRange();
      if (range == null
          || range.length != 4
          || range[0] != 0
          || range[1] <= 0
          || range[2] <= range[1]
          || range[3] < 0
          || (long) range[2] + range[3] > file.length) {
        return invalid(signature, "签名字节范围非法", trust);
      }
      if (file[range[1]] != '<' || file[range[2] - 1] != '>')
        return invalid(signature, "签名范围缺口不是 Contents", trust);
      // 将范围缺口与当前签名字典的 Contents 对齐，防止验证另一段无关 CMS。
      String hex =
          new String(
                  file,
                  range[1] + 1,
                  range[2] - range[1] - 2,
                  java.nio.charset.StandardCharsets.US_ASCII)
              .replaceAll("\\s", "");
      byte[] gap = org.bouncycastle.util.encoders.Hex.decode(hex);
      if (!java.util.Arrays.equals(gap, signature.getContents()))
        return invalid(signature, "签名范围与 Contents 不一致", trust);
      later = (long) range[2] + range[3] < file.length;
      var cms =
          new CMSSignedData(
              new CMSProcessableByteArray(signature.getSignedContent(file)),
              signature.getContents(file));
      var signers = cms.getSignerInfos().getSigners();
      if (signers.size() != 1) return invalid(signature, "CMS 签署者数量不受支持", trust);
      var info = signers.iterator().next();
      var matches =
          cms.getCertificates().getMatches(null).stream()
              .filter(holder -> info.getSID().match(holder))
              .toList();
      if (matches.size() != 1) return invalid(signature, "无法确定签署证书", trust);
      var converter = new JcaX509CertificateConverter();
      X509Certificate certificate = converter.getCertificate(matches.getFirst());
      signer = certificate.getSubjectX500Principal().getName();
      algorithm = info.getDigestAlgOID() + " / " + info.getEncryptionAlgOID();
      validity = validity(certificate);
      integrity =
          info.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificate.getPublicKey()))
              ? "VALID"
              : "INVALID";
      List<X509Certificate> chain = new ArrayList<>();
      for (var holder : cms.getCertificates().getMatches(null))
        chain.add(converter.getCertificate(holder));
      if (!anchors.isEmpty())
        trust = trusted(certificate, chain, anchors) ? "TRUSTED" : "UNTRUSTED";
      return new SignatureResult(
          signature.getName(),
          signer,
          algorithm,
          integrity,
          later,
          validity,
          trust,
          false,
          "VALID".equals(integrity) ? null : "CMS 摘要或签名不匹配");
    } catch (IOException
        | GeneralSecurityException
        | org.bouncycastle.cms.CMSException
        | org.bouncycastle.operator.OperatorCreationException
        | IllegalArgumentException
        | ClassCastException
        | IndexOutOfBoundsException exception) {
      return new SignatureResult(
          signature.getName(),
          signer,
          algorithm,
          "INVALID",
          later,
          validity,
          trust,
          false,
          "签名结构或密码学验证失败");
    }
  }

  private static SignatureResult invalid(PDSignature signature, String reason, String trust) {
    return new SignatureResult(
        signature.getName(), null, null, "INVALID", false, "UNKNOWN", trust, false, reason);
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
