package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.SigningOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record SigningMaterial(PrivateKey privateKey, List<X509Certificate> chain) {
  static SigningMaterial load(SigningOptions options) throws IOException {
    char[] password = options.certificatePassword();
    try {
      var store = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(options.certificate())) {
        store.load(input, password);
      }
      List<String> aliases = new ArrayList<>();
      var names = store.aliases();
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        if (store.isKeyEntry(name)) aliases.add(name);
      }
      String alias = options.alias();
      if (alias == null || alias.isBlank()) {
        if (aliases.size() != 1)
          throw new PdfProcessingException("CERTIFICATE_INVALID", "证书容器必须明确指定一个私钥条目");
        alias = aliases.getFirst();
      }
      if (!store.isKeyEntry(alias))
        throw new PdfProcessingException("CERTIFICATE_INVALID", "指定条目不包含私钥");
      var key = store.getKey(alias, password);
      if (!(key instanceof PrivateKey privateKey) || !"RSA".equalsIgnoreCase(key.getAlgorithm())) {
        throw new PdfProcessingException("CERTIFICATE_INVALID", "签名需要 RSA 私钥");
      }
      var certificates = store.getCertificateChain(alias);
      if (certificates == null || certificates.length == 0)
        throw new PdfProcessingException("CERTIFICATE_INVALID", "证书链为空");
      List<X509Certificate> chain = new ArrayList<>();
      for (var cert : certificates) {
        if (!(cert instanceof X509Certificate x509))
          throw new PdfProcessingException("CERTIFICATE_INVALID", "证书格式不受支持");
        x509.checkValidity();
        chain.add(x509);
      }
      boolean[] usage = chain.getFirst().getKeyUsage();
      if (usage != null && !usage[0] && !(usage.length > 1 && usage[1])) {
        throw new PdfProcessingException("CERTIFICATE_INVALID", "证书未授权数字签名用途");
      }
      return new SigningMaterial(privateKey, List.copyOf(chain));
    } catch (java.security.UnrecoverableKeyException exception) {
      throw new PdfProcessingException("CERTIFICATE_PASSWORD_INVALID", "证书密码错误", exception);
    } catch (GeneralSecurityException exception) {
      throw new PdfProcessingException("CERTIFICATE_INVALID", "证书或私钥不可用于签名", exception);
    } catch (IOException exception) {
      if (exception instanceof PdfProcessingException) throw exception;
      if (exception.getCause() instanceof java.security.UnrecoverableKeyException)
        throw new PdfProcessingException("CERTIFICATE_PASSWORD_INVALID", "证书密码错误", exception);
      throw new PdfProcessingException("CERTIFICATE_INVALID", "无法读取证书容器", exception);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
