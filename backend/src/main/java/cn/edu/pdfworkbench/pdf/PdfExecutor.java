package cn.edu.pdfworkbench.pdf;

import cn.edu.pdfworkbench.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfExecutor implements AutoCloseable {
  private final ObjectMapper mapper;
  private final Semaphore slots;
  private final int maxPages;
  private final long maxOutput;
  private final Path root;
  private final PdfProcessRunner runner = new PdfProcessRunner();
  private static final Map<String, Long> ATTACHMENTS = Map.of("certificate.p12",5242880L,
      "trust.cer",5242880L,"watermark.image",20971520L);

  @Autowired
  public PdfExecutor(ObjectMapper mapper,
      @Value("${workbench.max-concurrent-jobs:2}") int concurrency,
      @Value("${workbench.max-pages:200}") int maxPages,
      @Value("${workbench.max-output-bytes:209715200}") long maxOutput,
      @Value("${workbench.temporary-root:${java.io.tmpdir}}") String root) {
    if (concurrency < 1 || maxPages < 1 || maxOutput < 1) throw new IllegalArgumentException("Invalid PDF limits");
    this.mapper=mapper; this.slots=new Semaphore(concurrency); this.maxPages=maxPages;
    this.maxOutput=maxOutput; this.root=Path.of(root).toAbsolutePath();
  }

  @FunctionalInterface public interface ResultReader<T> {
    T read(Path input, Path output, PdfJobResult result) throws IOException;
  }

  public <T> T execute(MultipartFile file,String password,String operation,Object options,
      Map<String,MultipartFile> attachments,ResultReader<T> reader) throws IOException {
    if (!ATTACHMENTS.keySet().containsAll(attachments.keySet())) throw new IllegalArgumentException("Unknown attachment");
    if (!slots.tryAcquire()) throw new ApiException(429,"CAPACITY_EXCEEDED","同时处理任务已达上限");
    Path dir=null;
    Throwable failure=null;
    try {
      try { dir=Files.createTempDirectory(root,"pdf-workbench-"); }
      catch(IOException e) { throw new PdfProcessingException("OUTPUT_CREATE_FAILED","处理工作目录无法创建",e); }
      Path input=upload(file,dir.resolve("input.pdf"),52428800L);
      try(var stream=Files.newInputStream(input)) {
        if (!Arrays.equals(stream.readNBytes(5),new byte[]{37,80,68,70,45}))
          throw new ApiException(415,"UNSUPPORTED_FORMAT","文件不是 PDF 格式");
      }
      for(var entry:attachments.entrySet()) upload(entry.getValue(),dir.resolve(entry.getKey()),ATTACHMENTS.get(entry.getKey()));
      long limit=operation.equals("READING")?Math.min(maxOutput,33554432L):maxOutput;
      byte[] request=mapper.writeValueAsBytes(new PdfJobRequest(operation,password,mapper.valueToTree(options),operation.equals("READING")?Math.min(maxPages,200):maxPages,limit));
      try { runner.run(command(dir),dir,request,Duration.ofSeconds(90),536870912L); }
      finally { Arrays.fill(request,(byte)0); }
      Path manifest=dir.resolve("result.json");
      if(!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)||Files.size(manifest)>16384) throw invalid();
      PdfJobResult result;
      try { result=mapper.readValue(manifest.toFile(),PdfJobResult.class); }
      catch(IOException|RuntimeException e) { throw invalid(); }
      if(result==null||result.headers()==null) throw invalid();
      if(result.errorKind()!=null) {
        if(result.code()==null||!result.code().matches("[A-Z][A-Z0-9_]{0,79}")||result.message()==null
            ||result.message().length()>1024||result.status()<400||result.status()>599) throw invalid();
        switch(result.errorKind()) {
          case "API" -> throw new ApiException(result.status(),result.code(),result.message());
          case "PDF" -> throw new PdfProcessingException(result.code(),result.message());
          case "PARAMETER" -> throw new PdfParameterException(result.code(),result.message());
          case "INVALID" -> throw new IllegalArgumentException("Invalid PDF parameters");
          case "IO" -> throw new IOException("PDF document could not be parsed");
          default -> throw invalid();
        }
      }
      if(result.status()!=200||result.contentType()==null||!Set.of("application/json;charset=UTF-8",
          "application/pdf","text/csv;charset=UTF-8","application/zip").contains(result.contentType())) throw invalid();
      if(result.downloadName()!=null&&!Set.of("table.csv","images.zip","encrypted.pdf","signed.pdf",
          "unsigned.pdf","watermarked.pdf").contains(result.downloadName())) throw invalid();
      for(var h:result.headers().entrySet()) if(!Set.of("X-Images-Total","X-Images-Exported").contains(h.getKey())
          ||h.getValue()==null||!h.getValue().matches("[0-9]{1,10}")) throw invalid();
      Path output=dir.resolve("output.bin");
      if(!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS)) throw invalid();
      if(Files.size(output)>limit) throw new ApiException(413,"OUTPUT_LIMIT","输出数据超过大小限制");
      return reader.read(input,output,result);
    } catch(IOException|RuntimeException|Error e) { failure=e; throw e; }
    finally {
      try {
        if(dir!=null) try(var paths=Files.walk(dir)) {
          for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch(IOException cleanup) { if(failure!=null) failure.addSuppressed(cleanup); else throw cleanup; }
      } finally { slots.release(); }
    }
  }

  private static Path upload(MultipartFile file,Path target,long limit) throws IOException {
    if(file==null||file.isEmpty()) throw new ApiException(400,"FILE_REQUIRED","请选择文件");
    if(file.getSize()>limit) throw new ApiException(413,"FILE_TOO_LARGE","上传文件超过大小限制");
    try(var input=file.getInputStream();var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
      byte[] buffer=new byte[16384]; long total=0; int count;
      while((count=input.read(buffer))!=-1) {
        if(count>limit-total) throw new ApiException(413,"FILE_TOO_LARGE","上传文件超过大小限制");
        output.write(buffer,0,count); total+=count;
      }
    } catch(IOException e) { throw new PdfProcessingException("OUTPUT_CREATE_FAILED","上传暂存文件无法写入",e); }
    return target;
  }

  private static ApiException invalid() { return new ApiException(503,"PDF_RESULT_INVALID","PDF 处理结果不可用"); }

  static List<String> command(Path dir) throws IOException {
    String executable=Path.of(System.getProperty("java.home"),"bin",
        System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    var args=new ArrayList<String>(List.of(executable,"-Xmx384m","-XX:MaxDirectMemorySize=64m",
        "-XX:MaxMetaspaceSize=128m","-XX:-UsePerfData","-XX:+ExitOnOutOfMemoryError",
        "-XX:-HeapDumpOnOutOfMemoryError","-Djava.awt.headless=true","-Dfile.encoding=UTF-8",
        "-Djava.io.tmpdir="+dir,"-Duser.home="+dir,"-XX:ErrorFile="+dir.resolve("jvm-error.log")));
    String cp=System.getProperty("java.class.path");
    String[] entries=cp.split(java.util.regex.Pattern.quote(File.pathSeparator));
    if(entries.length==1&&entries[0].endsWith(".jar")) {
      Path jar=Path.of(entries[0]).toAbsolutePath();
      try(var archive=new java.util.jar.JarFile(jar.toFile())) {
        var manifest=archive.getManifest();
        if(manifest!=null&&"cn.edu.pdfworkbench.PdfWorkbenchApplication".equals(manifest.getMainAttributes().getValue("Start-Class"))) {
          args.addAll(List.of("-jar",jar.toString(),"--pdf-child")); return args;
        }
      }
    }
    cp=System.getProperty("surefire.test.class.path",cp);
    cp=Arrays.stream(cp.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        .map(s->Path.of(s).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    args.addAll(List.of("-cp",cp,"cn.edu.pdfworkbench.PdfWorkbenchApplication","--pdf-child")); return args;
  }

  @PreDestroy @Override public void close() throws IOException { runner.close(); }
}
