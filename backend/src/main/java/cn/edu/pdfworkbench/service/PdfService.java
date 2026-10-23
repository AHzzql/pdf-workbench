package cn.edu.pdfworkbench.service;

import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.pdf.PdfExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Synchronous HTTP adapter for the shared PDF execution boundary. */
@Service
public class PdfService {
  private final PdfExecutor executor;
  @Autowired public PdfService(PdfExecutor executor) { this.executor=executor; }
  public PdfService(ObjectMapper mapper,int concurrency,int maxPages,long maxOutput,String temporaryRoot) {
    this(new PdfExecutor(mapper,concurrency,maxPages,maxOutput,temporaryRoot));
  }
  private void run(MultipartFile file,String password,String operation,Object options,
      Map<String,MultipartFile> attachments,HttpServletResponse response) throws IOException {
    executor.execute(file,password,operation,options,attachments,(input,output,result)-> {
      response.setContentType(result.contentType());
      if(result.downloadName()!=null) response.setHeader("Content-Disposition","attachment; filename=\""+result.downloadName()+"\"");
      result.headers().forEach(response::setHeader);
      response.setContentLengthLong(Files.size(output));
      Files.copy(output,response.getOutputStream());
      return null;
    });
  }
  public void text(MultipartFile file,String password,TextOptions options,HttpServletResponse response) throws IOException {
    run(file,password,"TEXT",options,Map.of(),response);
  }
  public void search(MultipartFile file,String password,SearchOptions options,HttpServletResponse response) throws IOException {
    run(file,password,"SEARCH",options,Map.of(),response);
  }
  public void info(MultipartFile file,String password,HttpServletResponse response) throws IOException {
    run(file,password,"INFO",null,Map.of(),response);
  }
  public void tables(MultipartFile file,String password,TableOptions options,Integer index,HttpServletResponse response) throws IOException {
    Map<String,Object> parameters=new LinkedHashMap<>(); parameters.put("table",options); parameters.put("index",index);
    run(file,password,"TABLES",parameters,Map.of(),response);
  }
  public void images(MultipartFile file,String password,ImageFormat format,HttpServletResponse response) throws IOException {
    run(file,password,"IMAGES",Map.of("format",format),Map.of(),response);
  }
  public void encrypt(MultipartFile file,String password,EncryptionOptions options,HttpServletResponse response) throws IOException {
    run(file,password,"ENCRYPT",options,Map.of(),response);
  }
  public void sign(MultipartFile file,String password,MultipartFile certificate,String certificatePassword,
      String alias,String reason,String location,HttpServletResponse response) throws IOException {
    Map<String,Object> options=new LinkedHashMap<>(); options.put("certificatePassword",certificatePassword);
    options.put("alias",alias); options.put("reason",reason); options.put("location",location);
    Map<String,MultipartFile> attachments=new LinkedHashMap<>(); attachments.put("certificate.p12",certificate);
    run(file,password,"SIGN",options,attachments,response);
  }
  public void verify(MultipartFile file,String password,MultipartFile trust,HttpServletResponse response) throws IOException {
    boolean present=trust!=null&&!trust.isEmpty();
    run(file,password,"VERIFY",Map.of("trust",present),present?Map.of("trust.cer",trust):Map.of(),response);
  }
  public void remove(MultipartFile file,String password,boolean confirmed,HttpServletResponse response) throws IOException {
    if(!confirmed) throw new IllegalArgumentException("需确认移除签名");
    run(file,password,"REMOVE",Map.of("confirmed",true),Map.of(),response);
  }
  public void watermark(MultipartFile file,String password,TextWatermark text,ImageWatermark image,
      MultipartFile picture,HttpServletResponse response) throws IOException {
    Map<String,MultipartFile> attachments=new LinkedHashMap<>();
    if(text!=null) run(file,password,"WATERMARK_TEXT",text,Map.of(),response);
    else {
      attachments.put("watermark.image",picture);
      run(file,password,"WATERMARK_IMAGE",new ImageWatermark(image.pages(),null,image.x(),image.y(),
          image.width(),image.height(),image.angle(),image.opacity()),attachments,response);
    }
  }
}
