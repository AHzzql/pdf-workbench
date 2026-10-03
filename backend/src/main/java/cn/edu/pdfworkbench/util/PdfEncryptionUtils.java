package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.EncryptionOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** 使用 AES 加密新 PDF 并设置标准文档权限。 */
public final class PdfEncryptionUtils {
  private PdfEncryptionUtils() {}

  /**
   * 生成 AES 128/256 位加密副本；修改已有加密必须使用所有者密码。
   *
   * @param input 源 PDF
   * @param password 原文件打开密码
   * @param output 新文件，不能与源路径或源文件别名相同
   * @param options 新密码、密钥位数与权限
   * @throws IOException 文件、密码、权限或已签名文档错误
   * @throws IllegalArgumentException 参数不符合约束
   */
  public static void encrypt(Path input, String password, Path output, EncryptionOptions options)
      throws IOException {
    validate(options);
    PdfFiles.validateOutput(input, output);
    try (var document = PdfFiles.open(input, password)) {
      // StandardSecurityHandler 将用户密码得到的权限锁为只读；全部权限位开启并不等于使用了所有者密码。
      if (document.isEncrypted() && document.getCurrentAccessPermission().isReadOnly()) {
        throw new PdfProcessingException("PDF_PERMISSION_DENIED", "修改加密需要所有者权限");
      }
      if (!document.getSignatureDictionaries().isEmpty()) {
        throw new PdfProcessingException("SIGNED_DOCUMENT", "已签名文档不能直接重新加密");
      }
      var permissions = new AccessPermission();
      permissions.setCanPrint(options.allowPrint());
      permissions.setCanPrintFaithful(options.allowPrint());
      permissions.setCanExtractContent(options.allowCopy());
      permissions.setCanModify(options.allowModify());
      permissions.setCanModifyAnnotations(options.allowModify());
      permissions.setCanFillInForm(options.allowModify());
      permissions.setCanAssembleDocument(options.allowModify());
      var policy =
          new StandardProtectionPolicy(
              options.ownerPassword(),
              options.userPassword() == null ? "" : options.userPassword(),
              permissions);
      policy.setEncryptionKeyLength(options.keyLength());
      policy.setPreferAES(true);
      document.protect(policy);
      PdfFiles.save(document, output);
    }
  }

  private static void validate(EncryptionOptions options) {
    if (options == null || (options.keyLength() != 128 && options.keyLength() != 256)) {
      throw new IllegalArgumentException("AES 密钥长度必须为 128 或 256");
    }
    String owner = options.ownerPassword();
    String user = options.userPassword() == null ? "" : options.userPassword();
    if (owner == null || owner.isEmpty() || owner.equals(user)) {
      throw new IllegalArgumentException("所有者密码不能为空且必须与用户密码不同");
    }
    for (String value : new String[] {owner, user}) {
      if (options.keyLength() == 128) {
        if (value.length() > 32 || !StandardCharsets.ISO_8859_1.newEncoder().canEncode(value)) {
          throw new IllegalArgumentException("AES 128 密码限 32 个 Latin-1 字符");
        }
      } else if (value.getBytes(StandardCharsets.UTF_8).length > 127) {
        throw new IllegalArgumentException("AES 256 密码的 UTF-8 长度不能超过 127 字节");
      }
    }
  }
}
