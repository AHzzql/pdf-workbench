package cn.edu.pdfworkbench.model;

/** 签名完整性、后续修订、当前证书有效期及信任独立判定。 */
public record SignatureResult(
    String name,
    String signer,
    String algorithm,
    String integrity,
    boolean hasLaterRevision,
    String certificateValidity,
    String trustStatus,
    boolean revocationChecked,
    String reason) {}
