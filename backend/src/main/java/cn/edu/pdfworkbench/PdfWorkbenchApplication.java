package cn.edu.pdfworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class PdfWorkbenchApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--pdf-child".equals(args[0])) {
      System.exit(cn.edu.pdfworkbench.pdf.PdfChild.run(System.in, java.nio.file.Path.of(".")));
      return;
    }
    SpringApplication.run(PdfWorkbenchApplication.class, args);
  }
}
