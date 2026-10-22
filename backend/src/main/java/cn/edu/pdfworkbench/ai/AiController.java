package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Files;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/pdf/ai")
@Tag(name = "AI 智能阅读")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class AiController {
  private final AiRepository repository;
  private final AiDocuments documents;
  private final AiRuns runs;
  private final AiSettings settings;
  private final AiModelClient models;
  private final java.util.concurrent.Semaphore downloads = new java.util.concurrent.Semaphore(4);

  public AiController(
      AiRepository repository,
      AiDocuments documents,
      AiRuns runs,
      AiSettings settings,
      AiModelClient models) {
    this.repository = repository;
    this.documents = documents;
    this.runs = runs;
    this.settings = settings;
    this.models = models;
  }

  @GetMapping("/status")
  @Operation(summary = "查询模型配置状态")
  public Map<String, Object> status() {
    return Map.of("configured", settings.configured(), "model", settings.chatModel());
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "导入当前 PDF，提取可引用文本")
  public Document register(
      @RequestPart MultipartFile file, @RequestPart(required = false) String password)
      throws Exception {
    models.requireConfigured();
    return documents.register(file, password);
  }

  @GetMapping("/documents/{id}")
  @Operation(summary = "读取文档状态")
  public Document document(@PathVariable String id) {
    return repository.document(id);
  }

  @GetMapping("/documents/{id}/chapters")
  @Operation(summary = "读取文档目录")
  public List<Chapter> chapters(@PathVariable String id) throws Exception {
    readable(id);
    return repository.content(id).chapters();
  }

  @GetMapping("/documents/{id}/file")
  @Operation(summary = "读取历史会话关联的原始 PDF")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",content=@io.swagger.v3.oas.annotations.media.Content(
      mediaType="application/pdf",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
  public void file(@PathVariable String id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    readable(id);
    if (!downloads.tryAcquire()) throw new ApiException(503,"DOWNLOAD_CAPACITY","文件下载繁忙，请稍后重试");
    java.nio.file.Path directory = null;
    try {
      try {
        Files.createDirectories(repository.root());
        directory = Files.createTempDirectory(repository.root(),"download-");
      } catch (java.io.IOException e) { throw new ApiException(503,"DOWNLOAD_STORAGE_UNAVAILABLE","下载临时存储暂不可用"); }
      var pdf = directory.resolve("document.pdf");
      try { repository.pdf(id,pdf); }
      catch (java.io.IOException e) { throw new ApiException(503,"DOWNLOAD_STORAGE_UNAVAILABLE","下载临时存储暂不可用"); }
      readable(id); repository.requireAccess();
      response.setContentType(MediaType.APPLICATION_PDF_VALUE);
      response.setHeader("Content-Disposition","attachment; filename=\"document.pdf\"");
      response.setContentLengthLong(Files.size(pdf));
      try (var input = Files.newInputStream(pdf)) {
        byte[] buffer = new byte[65536]; long checked = System.nanoTime();
        for (int n; (n=input.read(buffer))!=-1;) {
          if (System.nanoTime()-checked>=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)) {
            repository.requireAccess(); readable(id); checked=System.nanoTime();
          }
          response.getOutputStream().write(buffer,0,n);
        }
      }
    } finally {
      try {
        if (directory!=null) { Files.deleteIfExists(directory.resolve("document.pdf")); Files.deleteIfExists(directory); }
      } finally { downloads.release(); }
    }
  }

  private void readable(String id) {
    if (repository.document(id).status().equals("DELETING"))
      throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
  }

  @DeleteMapping("/documents/{id}")
  @Operation(summary = "删除无会话引用的文档及索引")
  public void deleteDocument(@PathVariable String id) throws Exception {
    documents.delete(id);
  }

  @PostMapping("/conversations")
  @Operation(summary = "为单份 PDF 新建会话")
  public Conversation create(@RequestBody CreateConversation request) {
    return documents.createConversation(request.documentId());
  }

  @PostMapping("/conversations/search")
  @Operation(summary = "查询当前空间的历史会话")
  public List<Conversation> history(@RequestBody HistoryQuery request) {
    String query = Objects.toString(request.query(), "").strip().toLowerCase(Locale.ROOT);
    if (query.length() > 100) throw new ApiException(400, "INVALID_QUERY", "搜索词不超过 100 字符");
    return repository.conversations().stream()
        .filter(c -> c.title().toLowerCase(Locale.ROOT).contains(query))
        .limit(200)
        .toList();
  }

  @PatchMapping("/conversations/{id}")
  @Operation(summary = "重命名会话")
  public Conversation rename(@PathVariable String id, @RequestBody Rename request) {
    if (request.title() == null || request.title().isBlank() || request.title().length() > 80)
      throw new ApiException(400, "INVALID_TITLE", "会话名称为 1 至 80 字符");
    synchronized (runs) {
      var old = repository.conversation(id);
      var c =
          new Conversation(
              id,
              old.documentId(),
              request.title().strip(),
              old.createdAt(),
              System.currentTimeMillis());
      repository.save(c);
      return c;
    }
  }

  @DeleteMapping("/conversations/{id}")
  @Operation(summary = "删除会话，可清理无其他会话引用的关联 PDF")
  public void delete(@PathVariable String id, @RequestBody DeleteConversation request)
      throws Exception {
    synchronized (runs) {
      if (runs.busy(id)) throw new ApiException(409, "RUN_ACTIVE", "请先停止当前会话的生成");
      documents.deleteConversation(id, request.deleteDocument());
    }
  }

  @GetMapping("/conversations/{id}/messages")
  @Operation(summary = "读取历史消息和引用")
  public List<Message> messages(@PathVariable String id) {
    return repository.messages(id);
  }

  @PostMapping("/conversations/{id}/messages")
  @Operation(summary = "创建阅读任务；AUTO 自动识别任务与范围，WELCOME 生成会话概览；兼容 ASK、BRIEF、DETAILED、CHAPTER")
  public Message ask(@PathVariable String id, @RequestBody Ask request) {
    return runs.start(id, request, false);
  }

  @GetMapping("/runs/{id}")
  @Operation(summary = "恢复生成任务快照")
  public Message run(@PathVariable String id) {
    return runs.snapshot(id);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "订阅带顺序号的完整消息快照，支持重连")
  public SseEmitter events(@PathVariable String id) throws Exception {
    return runs.events(id);
  }

  @PostMapping("/runs/{id}/cancel")
  @Operation(summary = "停止生成并取消模型请求")
  public Message cancel(@PathVariable String id) {
    return runs.cancel(id);
  }

  @PostMapping("/messages/{id}/retry")
  @Operation(summary = "重新生成回答，不重复创建用户消息")
  public Message retry(@PathVariable String id, @RequestBody Map<String, String> request) {
    return runs.retry(id, request.get("requestId"));
  }
}
