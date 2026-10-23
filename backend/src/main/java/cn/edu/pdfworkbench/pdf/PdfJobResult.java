package cn.edu.pdfworkbench.pdf;
import java.util.Map;
public record PdfJobResult(String contentType,String downloadName,Map<String,String> headers,
    String errorKind,int status,String code,String message) {}
