package cn.edu.pdfworkbench.model;

/** 新密码、AES 位数及独立权限；字符串表示不包含密码。 */
public record EncryptionOptions(
    String userPassword,
    String ownerPassword,
    int keyLength,
    boolean allowPrint,
    boolean allowCopy,
    boolean allowModify) {
  @Override
  public String toString() {
    return "EncryptionOptions[keyLength="
        + keyLength
        + ", allowPrint="
        + allowPrint
        + ", allowCopy="
        + allowCopy
        + ", allowModify="
        + allowModify
        + "]";
  }
}
