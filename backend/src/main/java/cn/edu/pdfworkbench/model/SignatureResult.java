package cn.edu.pdfworkbench.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 签名完整性、后续修订、当前证书有效期及信任独立判定。 */
public record SignatureResult(
    String name,
    String signer,
    String algorithm,
    @Schema(allowableValues = {"VALID", "INVALID", "UNKNOWN"}) String integrity,
    @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Boolean hasLaterRevision,
    @Schema(allowableValues = {"VALID", "EXPIRED", "NOT_YET_VALID", "UNKNOWN"}) String certificateValidity,
    @Schema(allowableValues = {"TRUSTED", "UNTRUSTED", "NOT_EVALUATED"}) String trustStatus,
    boolean revocationChecked,
    @Schema(nullable = true) CertificateSummary certificate,
    String reason) {
  public record CertificateSummary(
      String subject, String issuer, String serialNumber, Instant notBefore, Instant notAfter) {}
}
