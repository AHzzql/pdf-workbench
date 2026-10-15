package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Files;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/pdf/ai")
@Tag(name = "AI 智能阅读")
public class AiController {
  private final AiRepository repository;
  private final AiDocuments documents;
  private final AiRuns runs;
  private final AiSettings settings;
  private final AiModelClient models;

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
    repository.document(id);
    return repository.content(id).chapters();
  }

  @GetMapping("/documents/{id}/file")
  @Operation(summary = "读取历史会话关联的原始 PDF")
  public ResponseEntity<FileSystemResource> file(@PathVariable String id) {
    repository.document(id);
    var p = repository.documentPath(id);
    if (!Files.exists(p)) throw new ApiException(404, "PDF_MISSING", "关联的 PDF 文件已不存在");
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header("Content-Disposition", "attachment; filename=\"document.pdf\"")
        .body(new FileSystemResource(p));
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
  @Operation(summary = "查询本机历史会话")
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
  @Operation(summary = "创建问答或摘要任务；mode 为 ASK、BRIEF、DETAILED 或 CHAPTER")
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
