package cn.edu.pdfworkbench.service;

import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfService {
  private final ObjectMapper mapper;
  private final Semaphore slots;
  private final int maxPages;
  private final long maxOutput;
  private final Path temporaryRoot;

  public PdfService(
      ObjectMapper mapper,
      @Value("${workbench.max-concurrent-jobs:2}") int concurrency,
      @Value("${workbench.max-pages:200}") int maxPages,
      @Value("${workbench.max-output-bytes:209715200}") long maxOutput,
      @Value("${workbench.temporary-root:${java.io.tmpdir}}") String temporaryRoot) {
    this.mapper = mapper;
    this.slots = new Semaphore(concurrency);
    this.maxPages = maxPages;
    this.maxOutput = maxOutput;
    this.temporaryRoot = Path.of(temporaryRoot);
  }

  @FunctionalInterface
  private interface Job {
    void run(Path input, Path directory) throws IOException;
  }

  private void run(MultipartFile file, String password, Job job) throws IOException {
    if (!slots.tryAcquire()) throw new ApiException(429, "CAPACITY_EXCEEDED", "同时处理任务已达上限");
    Path directory = null;
    try {
      try {
        directory = Files.createTempDirectory(temporaryRoot, "pdf-workbench-");
      } catch (IOException e) {
        throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "处理工作目录无法创建", e);
      }
      Path input = upload(file, directory, "input.pdf", 50L * 1024 * 1024);
      try (var stream = Files.newInputStream(input)) {
        String magic = new String(stream.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII);
        if (!"%PDF-".equals(magic))
          throw new ApiException(415, "UNSUPPORTED_FORMAT", "文件不是 PDF 格式");
      }
      try (var document = Loader.loadPDF(input.toFile(), password == null ? "" : password)) {
        if (document.getNumberOfPages() > maxPages)
          throw new ApiException(413, "PAGE_LIMIT", "文档页数超过限制");
      } catch (InvalidPasswordException e) {
        throw PdfProcessingException.password(password, e);
      }
      job.run(input, directory);
    } finally {
      try {
        if (directory != null) delete(directory);
      } finally {
        slots.release();
      }
    }
  }

  private static void delete(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  private static Path upload(MultipartFile file, Path directory, String name, long limit)
      throws IOException {
    if (file == null || file.isEmpty()) throw new ApiException(400, "FILE_REQUIRED", "请选择文件");
    if (file.getSize() > limit) throw new ApiException(413, "FILE_TOO_LARGE", "上传文件超过大小限制");
    Path target = directory.resolve(name);
    try { file.transferTo(target); }
    catch (IOException e) { throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "上传暂存文件无法写入", e); }
    if (Files.size(target) > limit) throw new ApiException(413, "FILE_TOO_LARGE", "上传文件超过大小限制");
    return target;
  }

  private void json(HttpServletResponse response, Object result) throws IOException {
    byte[] bytes = mapper.writeValueAsBytes(result);
    checkSize(bytes.length);
    response.setContentType("application/json;charset=UTF-8");
    response.setContentLengthLong(bytes.length);
    response.getOutputStream().write(bytes);
  }

  private void checkSize(long size) {
    if (size > maxOutput) throw new ApiException(413, "OUTPUT_LIMIT", "输出数据超过大小限制");
  }

  private void download(HttpServletResponse response, Path output, String type, String name)
      throws IOException {
    checkSize(Files.size(output));
    response.setContentType(type);
    response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
    response.setContentLengthLong(Files.size(output));
    Files.copy(output, response.getOutputStream());
  }

  public void text(
      MultipartFile file, String password, TextOptions options, HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> json(response, PdfTextUtils.extract(input, password, options)));
  }

  public void search(
      MultipartFile file, String password, SearchOptions options, HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> json(response, PdfTextUtils.search(input, password, options)));
  }

  public void info(MultipartFile file, String password, HttpServletResponse response)
      throws IOException {
    run(file, password, (input, dir) -> json(response, PdfInfoUtils.read(input, password)));
  }

  public void tables(
      MultipartFile file,
      String password,
      TableOptions options,
      Integer index,
      HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          var tables = PdfTableUtils.extract(input, password, options);
          if (index == null) {
            json(response, tables);
            return;
          }
          if (index < 0 || index >= tables.size()) throw new IllegalArgumentException("表格索引越界");
          Path output = dir.resolve("table.csv");
          PdfTableUtils.writeCsv(tables.get(index), output);
          download(response, output, "text/csv;charset=UTF-8", "table.csv");
        });
  }

  public void images(
      MultipartFile file, String password, ImageFormat format, HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          var raw = PdfImageUtils.extract(input, password);
          if (raw.isEmpty()) throw new ApiException(422, "NO_IMAGES_FOUND", "文档没有可提取图片");
          var exported = PdfImageUtils.export(input, password, dir, format);
          long total = 0;
          List<Map<String, Object>> manifest = new ArrayList<>();
          Path output = dir.resolve("images.zip");
          try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (var image : raw) {
              byte[] bytes = image.rawData();
              total += bytes.length;
              checkSize(total);
              entry(zip, image.id() + ".bin", bytes);
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("id", image.id());
              item.put("rawFile", image.id() + ".bin");
              item.put("source", image.source());
              item.put("width", image.width());
              item.put("height", image.height());
              item.put("filters", image.filters());
              item.put("occurrences", image.occurrences());
              var match =
                  exported.stream().filter(value -> value.id().equals(image.id())).findFirst();
              if (match.isPresent() && match.get().file() != null) {
                var path = match.get().file();
                total += Files.size(path);
                checkSize(total);
                String name = image.id() + "." + format.name().toLowerCase(Locale.ROOT);
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(path, zip);
                zip.closeEntry();
                item.put("file", name);
                item.put("status", "EXPORTED");
              } else {
                item.put("status", "RAW_ONLY");
                item.put("diagnostic", match.map(ImageExport::diagnostic).orElse("图片不可解码"));
              }
              manifest.add(item);
            }
            byte[] bytes = mapper.writeValueAsBytes(manifest);
            checkSize(total + bytes.length);
            entry(zip, "manifest.json", bytes);
          } catch (PdfProcessingException e) {
            throw e;
          } catch (IOException e) {
            throw new PdfProcessingException("OUTPUT_CREATE_FAILED", "图片压缩包无法写入", e);
          }
          response.setHeader("X-Images-Total", Integer.toString(raw.size()));
          response.setHeader("X-Images-Exported", Long.toString(manifest.stream()
              .filter(item -> "EXPORTED".equals(item.get("status"))).count()));
          download(response, output, "application/zip", "images.zip");
        });
  }

  private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  public void encrypt(
      MultipartFile file, String password, EncryptionOptions options, HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          Path output = dir.resolve("encrypted.pdf");
          PdfEncryptionUtils.encrypt(input, password, output, options);
          download(response, output, "application/pdf", "encrypted.pdf");
        });
  }

  public void sign(
      MultipartFile file,
      String password,
      MultipartFile certificate,
      String certificatePassword,
      String alias,
      String reason,
      String location,
      HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          Path key = upload(certificate, dir, "certificate.p12", 5L * 1024 * 1024);
          char[] secret = certificatePassword.toCharArray();
          try (var options = new SigningOptions(key, secret, alias, reason, location)) {
            Path output = dir.resolve("signed.pdf");
            PdfSignatureUtils.sign(input, password, output, options);
            download(response, output, "application/pdf", "signed.pdf");
          } finally {
            Arrays.fill(secret, '\0');
          }
        });
  }

  public void verify(
      MultipartFile file, String password, MultipartFile trust, HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          List<X509Certificate> anchors = new ArrayList<>();
          if (trust != null && !trust.isEmpty()) {
            Path path = upload(trust, dir, "trust.cer", 5L * 1024 * 1024);
            try (var stream = Files.newInputStream(path)) {
              for (var certificate :
                  CertificateFactory.getInstance("X.509").generateCertificates(stream))
                anchors.add((X509Certificate) certificate);
              if (anchors.isEmpty()) throw new CertificateException("empty");
            } catch (CertificateException e) {
              throw new PdfProcessingException("CERTIFICATE_INVALID", "信任证书格式错误", e);
            }
          }
          json(response, PdfSignatureUtils.verify(input, password, anchors));
        });
  }

  public void remove(
      MultipartFile file, String password, boolean confirmed, HttpServletResponse response)
      throws IOException {
    if (!confirmed) throw new IllegalArgumentException("需确认移除签名");
    run(
        file,
        password,
        (input, dir) -> {
          Path output = dir.resolve("unsigned.pdf");
          PdfSignatureUtils.remove(input, password, output);
          download(response, output, "application/pdf", "unsigned.pdf");
        });
  }

  public void watermark(
      MultipartFile file,
      String password,
      TextWatermark text,
      ImageWatermark image,
      MultipartFile picture,
      HttpServletResponse response)
      throws IOException {
    run(
        file,
        password,
        (input, dir) -> {
          Path output = dir.resolve("watermarked.pdf");
          if (text != null) PdfWatermarkUtils.addText(input, password, output, text);
          else {
            Path mark = upload(picture, dir, "watermark.image", 20L * 1024 * 1024);
            PdfWatermarkUtils.addImage(
                input,
                password,
                output,
                new ImageWatermark(
                    image.pages(),
                    mark,
                    image.x(),
                    image.y(),
                    image.width(),
                    image.height(),
                    image.angle(),
                    image.opacity()));
          }
          download(response, output, "application/pdf", "watermarked.pdf");
        });
  }
}
