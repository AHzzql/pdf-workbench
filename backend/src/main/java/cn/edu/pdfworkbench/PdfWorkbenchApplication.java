package cn.edu.pdfworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
public class PdfWorkbenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(PdfWorkbenchApplication.class, args);
  }
}
