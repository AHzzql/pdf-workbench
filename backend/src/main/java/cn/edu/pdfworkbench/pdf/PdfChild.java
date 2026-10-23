package cn.edu.pdfworkbench.pdf;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.exception.*;
import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.util.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.cert.*;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** Standalone PDF dispatcher; no application context or infrastructure credentials. */
public final class PdfChild {
  private final ObjectMapper mapper=mapper();
  private static ObjectMapper mapper() {
    return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
  private final Path directory;
  private final PdfJobRequest request;
  private final Path input;
  private final Path output;
  private PdfChild(Path directory,PdfJobRequest request) {
    this.directory=directory; this.request=request;
    input=directory.resolve("input.pdf"); output=directory.resolve("output.bin");
  }
  public static int run(InputStream input,Path directory) {
    var mapper=mapper();
    PdfJobResult result;
    try {
      byte[] bytes=input.readNBytes(65537);
      PdfJobRequest request;
      try {
        if(bytes.length>65536) throw new IllegalArgumentException("Request too large");
        request=mapper.readValue(bytes,PdfJobRequest.class);
      } finally { Arrays.fill(bytes,(byte)0); }
      if(request==null||request.operation()==null||request.maxPages()<1||request.maxOutput()<1)
        throw new IllegalArgumentException("Invalid request");
      result=new PdfChild(directory,request).execute();
    } catch(ApiException e) { result=error("API",e.status(),e.code(),e.getMessage()); }
    catch(PdfProcessingException e) { result=error("PDF",422,e.getCode(),e.getMessage()); }
    catch(PdfParameterException e) { result=error("PARAMETER",400,e.getCode(),e.getMessage()); }
    catch(IllegalArgumentException e) { result=error("INVALID",400,"INVALID_PARAMETER","请求参数缺失或格式不正确"); }
    catch(IOException e) { result=error("IO",422,"DOCUMENT_INVALID","文件无法解析"); }
    catch(Exception e) { result=error("API",500,"INTERNAL_ERROR","处理失败"); }
    try {
      Path temporary=directory.resolve("result.tmp");
      mapper.writeValue(temporary.toFile(),result);
      Files.move(temporary,directory.resolve("result.json"),StandardCopyOption.ATOMIC_MOVE);
      return 0;
    } catch(IOException e) { return 1; }
  }
  private static PdfJobResult error(String kind,int status,String code,String message) {
    return new PdfJobResult(null,null,Map.of(),kind,status,code,message);
  }
  private PdfJobResult success(String type,String name) {
    return new PdfJobResult(type,name,Map.of(),null,200,null,null);
  }
  private <T> T options(Class<T> type) throws IOException { return mapper.treeToValue(request.options(),type); }
  private JsonNode field(String name) { return request.options().path(name); }
  private String string(String name) { var value=field(name); return value.isNull()||value.isMissingNode()?null:value.asText(); }
  private PdfJobResult execute() throws IOException {
    String password=request.password();
    int pages; boolean encrypted;
    try(var document=Loader.loadPDF(input.toFile(),password==null?"":password)) {
      pages=document.getNumberOfPages(); encrypted=document.isEncrypted();
      if(pages>request.maxPages()) throw new ApiException(413,"PAGE_LIMIT","文档页数超过限制");
    } catch(InvalidPasswordException e) { throw PdfProcessingException.password(password,e); }
    return switch(request.operation()) {
      case "TEXT" -> json(PdfTextUtils.extract(input,password,options(TextOptions.class)));
      case "SEARCH" -> json(PdfTextUtils.search(input,password,options(SearchOptions.class)));
      case "INFO" -> json(PdfInfoUtils.read(input,password));
      case "TABLES" -> {
        var tables=PdfTableUtils.extract(input,password,mapper.treeToValue(field("table"),TableOptions.class));
        if(field("index").isNull()||field("index").isMissingNode()) yield json(tables);
        int index=field("index").asInt(-1);
        if(index<0||index>=tables.size()) throw new IllegalArgumentException("Invalid table index");
        Path csv=directory.resolve("table.csv"); PdfTableUtils.writeCsv(tables.get(index),csv);
        yield copy(csv,"text/csv;charset=UTF-8","table.csv");
      }
      case "IMAGES" -> images(password,ImageFormat.valueOf(field("format").asText()));
      case "ENCRYPT" -> {
        Path pdf=directory.resolve("encrypted.pdf");
        PdfEncryptionUtils.encrypt(input,password,pdf,options(EncryptionOptions.class));
        yield copy(pdf,"application/pdf","encrypted.pdf");
      }
      case "SIGN" -> {
        char[] secret=Objects.toString(string("certificatePassword"),"").toCharArray();
        try(var opts=new SigningOptions(directory.resolve("certificate.p12"),secret,string("alias"),string("reason"),string("location"))) {
          Path pdf=directory.resolve("signed.pdf"); PdfSignatureUtils.sign(input,password,pdf,opts);
          yield copy(pdf,"application/pdf","signed.pdf");
        } finally { Arrays.fill(secret,'\0'); }
      }
      case "VERIFY" -> {
        var anchors=new ArrayList<X509Certificate>();
        if(field("trust").asBoolean(false)) try(var stream=Files.newInputStream(directory.resolve("trust.cer"))) {
          for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(stream)) anchors.add((X509Certificate)certificate);
          if(anchors.isEmpty()) throw new CertificateException("empty");
        } catch(CertificateException e) { throw new PdfProcessingException("CERTIFICATE_INVALID","信任证书格式错误",e); }
        yield json(PdfSignatureUtils.verify(input,password,anchors));
      }
      case "REMOVE" -> {
        if(!field("confirmed").asBoolean(false)) throw new IllegalArgumentException("Confirmation required");
        Path pdf=directory.resolve("unsigned.pdf"); PdfSignatureUtils.remove(input,password,pdf);
        yield copy(pdf,"application/pdf","unsigned.pdf");
      }
      case "WATERMARK_TEXT" -> {
        Path pdf=directory.resolve("watermarked.pdf"); PdfWatermarkUtils.addText(input,password,pdf,options(TextWatermark.class));
        yield copy(pdf,"application/pdf","watermarked.pdf");
      }
      case "WATERMARK_IMAGE" -> {
        var mark=options(ImageWatermark.class); Path pdf=directory.resolve("watermarked.pdf");
        PdfWatermarkUtils.addImage(input,password,pdf,new ImageWatermark(mark.pages(),directory.resolve("watermark.image"),mark.x(),mark.y(),mark.width(),mark.height(),mark.angle(),mark.opacity()));
        yield copy(pdf,"application/pdf","watermarked.pdf");
      }
      case "READING" -> {
        if(pages<1) throw new ApiException(413,"PAGE_LIMIT","AI 阅读支持 1 至 200 页 PDF");
        var text=PdfTextUtils.extract(input,password,new TextOptions(null,true));
        if(text.pages().stream().mapToLong(p->p.text().length()).sum()>2000000)
          throw new ApiException(413,"TEXT_LIMIT","文档文字量超过 AI 处理限制");
        var content=AiChunker.split(text,PdfInfoUtils.read(input,password).bookmarks());
        if(content.chunks().isEmpty()) throw new ApiException(422,"NO_TEXT","文档没有可提取文字，当前不支持扫描件 OCR");
        if(content.chunks().size()>4000) throw new ApiException(413,"CHUNK_LIMIT","文档片段数量超过限制");
        yield json(new ParsedReadingDocument(pages,encrypted,content));
      }
      default -> throw new IllegalArgumentException("Unknown operation");
    };
  }
  private OutputStream bounded() throws IOException {
    return new FilterOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
      long count;
      private void check(int length) { if(length>request.maxOutput()-count) throw new ApiException(413,"OUTPUT_LIMIT","输出数据超过大小限制"); count+=length; }
      @Override public void write(int b) throws IOException { check(1); out.write(b); }
      @Override public void write(byte[] b,int offset,int length) throws IOException { check(length); out.write(b,offset,length); }
    };
  }
  private PdfJobResult json(Object value) throws IOException {
    try(var stream=bounded()) { mapper.writeValue(stream,value); }
    return success("application/json;charset=UTF-8",null);
  }
  private PdfJobResult copy(Path path,String type,String name) throws IOException {
    if(Files.size(path)>request.maxOutput()) throw new ApiException(413,"OUTPUT_LIMIT","输出数据超过大小限制");
    try(var stream=bounded()) { Files.copy(path,stream); }
    return success(type,name);
  }
  private PdfJobResult images(String password,ImageFormat format) throws IOException {
    var raw=PdfImageUtils.extract(input,password);
    if(raw.isEmpty()) throw new ApiException(422,"NO_IMAGES_FOUND","文档没有可提取图片");
    var exported=PdfImageUtils.export(input,password,directory,format);
    List<Map<String,Object>> manifest=new ArrayList<>(); long total=0;
    try(var zip=new ZipOutputStream(bounded())) {
      for(var image:raw) {
        total+=image.rawData().length; checkSize(total);
        zip.putNextEntry(new ZipEntry(image.id()+".bin")); zip.write(image.rawData()); zip.closeEntry();
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",image.id()); item.put("rawFile",image.id()+".bin"); item.put("source",image.source());
        item.put("width",image.width()); item.put("height",image.height()); item.put("filters",image.filters()); item.put("occurrences",image.occurrences());
        var match=exported.stream().filter(v->v.id().equals(image.id())).findFirst();
        if(match.isPresent()&&match.get().file()!=null) {
          var path=match.get().file(); total+=Files.size(path); checkSize(total);
          String name=image.id()+"."+format.name().toLowerCase(Locale.ROOT);
          zip.putNextEntry(new ZipEntry(name)); Files.copy(path,zip); zip.closeEntry();
          item.put("file",name); item.put("status","EXPORTED");
        } else { item.put("status","RAW_ONLY"); item.put("diagnostic",match.map(ImageExport::diagnostic).orElse("图片不可解码")); }
        manifest.add(item);
      }
      byte[] bytes=mapper.writeValueAsBytes(manifest); checkSize(total+bytes.length);
      zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry();
    }
    return new PdfJobResult("application/zip","images.zip",Map.of("X-Images-Total",Integer.toString(raw.size()),
        "X-Images-Exported",Long.toString(manifest.stream().filter(v->"EXPORTED".equals(v.get("status"))).count())),null,200,null,null);
  }
  private void checkSize(long size) { if(size>request.maxOutput()) throw new ApiException(413,"OUTPUT_LIMIT","输出数据超过大小限制"); }
}
