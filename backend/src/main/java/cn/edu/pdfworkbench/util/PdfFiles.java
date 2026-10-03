package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PdfFiles {
    private PdfFiles() { }

    static PDDocument openForExtraction(Path input, String password) throws IOException {
        if (input == null || !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("输入文件不存在");
        }
        PDDocument document;
        try {
            document = Loader.loadPDF(input.toFile(), password == null ? "" : password);
        } catch (InvalidPasswordException exception) {
            throw new PdfProcessingException("PDF_PASSWORD_INVALID", "PDF 打开密码错误", exception);
        }
        if (!document.getCurrentAccessPermission().canExtractContent()) {
            var error = new PdfProcessingException("PDF_PERMISSION_DENIED", "文档不允许提取内容");
            try {
                document.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
        return document;
    }
}
