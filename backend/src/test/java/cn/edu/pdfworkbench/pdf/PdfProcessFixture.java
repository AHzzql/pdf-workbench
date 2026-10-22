package cn.edu.pdfworkbench.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class PdfProcessFixture {
  public static void main(String[] arguments) throws Exception {
    Files.writeString(Path.of("child.pid"), Long.toString(ProcessHandle.current().pid()));
    switch (arguments[0]) {
      case "echo" -> {
        int length = System.in.readAllBytes().length;
        boolean clean = System.getenv().keySet().stream().allMatch(name ->
            java.util.Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP").contains(name.toUpperCase(java.util.Locale.ROOT)));
        Files.writeString(Path.of("result.txt"), length + ":" + clean);
      }
      case "hang" -> Thread.sleep(60000);
      case "crash" -> { System.err.print("fixture-private-detail"); System.exit(7); }
      case "grow" -> {
        try (var output = Files.newOutputStream(Path.of("output.bin"))) {
          for (int i = 0; i < 1000; i++) { output.write(new byte[8192]); output.flush(); Thread.sleep(2); }
        }
      }
      case "memory" -> {
        var buffers = new ArrayList<byte[]>();
        while (true) buffers.add(new byte[1024 * 1024]);
      }
      default -> throw new IllegalArgumentException();
    }
  }
}
