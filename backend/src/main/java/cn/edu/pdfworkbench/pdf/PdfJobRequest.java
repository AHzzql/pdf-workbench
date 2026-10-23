package cn.edu.pdfworkbench.pdf;
import com.fasterxml.jackson.databind.JsonNode;
public record PdfJobRequest(String operation,String password,JsonNode options,int maxPages,long maxOutput) {
  @Override public String toString() { return "PdfJobRequest[operation="+operation+", parameters=REDACTED]"; }
}
