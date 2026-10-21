package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.tasks.TaskStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@ConditionalOnWebApplication
public class AiRuns {
  private final AiRepository repository;
  private final AiModelClient models;
  private final TaskStore tasks;
  private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor();
  private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private static final Set<String> MODES = Set.of("AUTO","WELCOME","ASK","BRIEF","DETAILED","CHAPTER");
  private static final class Subscription {
    final String owner, id;
    final BooleanSupplier access;
    final SseEmitter emitter;
    long sequence = -1, sent;
    String stage = "";
    Subscription(String owner, String id, BooleanSupplier access, SseEmitter emitter) {
      this.owner=owner; this.id=id; this.access=access; this.emitter=emitter;
    }
  }

  public AiRuns(AiRepository repository, AiModelClient models, TaskStore tasks) {
    this.repository=repository; this.models=models; this.tasks=tasks;
    polling.scheduleWithFixedDelay(() -> subscriptions.forEach(this::emit), 500, 500, TimeUnit.MILLISECONDS);
  }

  public Message start(String conversationId, Ask ask, boolean retry) {
    return tasks.submission(() -> submit(conversationId, ask, retry));
  }

  private Message submit(String conversationId, Ask ask, boolean retry) {
    models.requireConfigured();
    AiRepository.validateId(ask.requestId());
    if (ask.mode() == null
        || !MODES.contains(ask.mode())
        || ask.question() == null
        || ask.question().length() > 2000
        || Set.of("ASK", "AUTO").contains(ask.mode()) && ask.question().isBlank())
      throw new ApiException(400, "INVALID_QUESTION", "问题不能为空且不超过 2000 字符");
    if (ask.mode().equals("CHAPTER")
        && (ask.pages() == null || ask.pages().isBlank())
        && (ask.chapterId() == null || ask.chapterId().isBlank()))
      throw new ApiException(400, "SCOPE_REQUIRED", "章节摘要需要章节或页码范围");
    var conversation = repository.conversation(conversationId);
    try {
      var previous = repository.message(ask.requestId());
      if (!previous.conversationId().equals(conversationId))
        throw new ApiException(409, "REQUEST_CONFLICT", "请求标识已被其他会话使用");
    } catch (ApiException e) {
      if (e.status() != 404) throw e;
    }
    var all = repository.messages(conversationId);
    var existing = all.stream().filter(m -> m.id().equals(ask.requestId())).findFirst();
    if (existing.isPresent()) {
      var m = existing.get();
      if (!m.role().equals("assistant")
          || !m.mode().equals(ask.mode())
          || !m.question().equals(ask.question())
          || !Objects.equals(m.pages(), ask.pages())
          || !Objects.equals(m.chapterId(), ask.chapterId()))
        throw new ApiException(409, "REQUEST_CONFLICT", "重复请求的内容不一致");
      return m;
    }
    if (ask.mode().equals("WELCOME")) {
      if (!ask.question().isBlank() || ask.pages() != null || ask.chapterId() != null)
        throw new ApiException(400, "INVALID_WELCOME", "文档概览不接受问题或阅读范围");
      if (!retry && !all.isEmpty()) throw new ApiException(409, "WELCOME_EXISTS", "当前会话已存在消息");
    }
    if (ask.mode().equals("AUTO") && (ask.pages() != null || ask.chapterId() != null))
      throw new ApiException(400, "INVALID_SCOPE", "自动阅读的范围由问题中的页码或章节确定");
    if (busy(conversationId))
      throw new ApiException(429, "AI_BUSY", "生成任务已满，或当前会话正在生成");
    var doc = repository.document(conversation.documentId());
    try {
      AiReading.select(repository.content(doc.id()), ask.pages(), ask.chapterId(), doc.pages());
    } catch (IOException e) {
      throw new ApiException(500, "AI_STORAGE_ERROR", "文档索引数据无法读取");
    }
    long now = System.currentTimeMillis();
    if (!retry && !ask.mode().equals("WELCOME"))
      repository.save(
          new Message(
              UUID.randomUUID().toString(),
              conversationId,
              "user",
              ask.question().isBlank()
                  ? switch (ask.mode()) {
                    case "BRIEF" -> "生成简要摘要";
                    case "CHAPTER" -> "生成章节摘要";
                    default -> "生成详细摘要";
                  }
                  : ask.question(),
              "COMPLETED",
              "",
              ask.mode(),
              ask.question(),
              ask.pages(),
              ask.chapterId(),
              List.of(),
              now,
              0));
    var message =
        new Message(
            ask.requestId(),
            conversationId,
            "assistant",
            "",
            "RUNNING",
            "",
            ask.mode(),
            ask.question(),
            ask.pages(),
            ask.chapterId(),
            List.of(),
            now + 1,
            0);
    repository.save(message);
    String title = conversation.title();
    if (title.equals("新对话") && !ask.mode().equals("WELCOME")) {
      title = ask.question().isBlank() ? "文档摘要" : ask.question();
      title = title.substring(0, Math.min(title.length(), 48));
    }
    repository.save(
        new Conversation(
            conversation.id(), conversation.documentId(), title, conversation.createdAt(), now));
    tasks.enqueue(repository.owner(), TaskStore.Kind.GENERATE, message.id(), doc.id(), conversationId);
    return message;
  }

  public boolean busy(String conversationId) { return tasks.busy(repository.owner(), conversationId); }
  public Message snapshot(String id) { return repository.message(id); }

  public SseEmitter events(String id) throws IOException {
    repository.message(id);
    if (subscriptions.size() >= 128) throw new ApiException(429, "STREAM_CAPACITY", "订阅连接已满，请稍后重试");
    var emitter = new SseEmitter(120000L);
    var subscription = new Subscription(repository.owner(), id, repository.access(), emitter);
    emitter.onCompletion(() -> subscriptions.remove(subscription));
    emitter.onTimeout(() -> subscriptions.remove(subscription));
    emitter.onError(error -> subscriptions.remove(subscription));
    subscriptions.add(subscription); emit(subscription);
    return emitter;
  }

  private void emit(Subscription subscription) {
    try {
      if (!subscription.access.getAsBoolean()) { subscriptions.remove(subscription); subscription.emitter.complete(); return; }
      try (var scope = repository.scope(subscription.owner, subscription.access)) {
        var message = repository.message(subscription.id);
        var task = tasks.forMessage(subscription.owner, subscription.id);
        String stage = task == null ? message.status() : task.stage();
        if (message.sequence() != subscription.sequence || !stage.equals(subscription.stage) || System.currentTimeMillis()-subscription.sent >= 15000) {
          subscription.emitter.send(SseEmitter.event().name("snapshot").id(Long.toString(message.sequence()))
              .data(Map.of("message", message, "stage", stage)));
          subscription.sequence=message.sequence(); subscription.stage=stage; subscription.sent=System.currentTimeMillis();
        }
        if (!message.status().equals("RUNNING")) { subscriptions.remove(subscription); subscription.emitter.complete(); }
      }
    } catch (Exception e) { subscriptions.remove(subscription); subscription.emitter.complete(); }
  }

  public Message cancel(String id) {
    repository.message(id);
    tasks.cancelMessage(repository.owner(), id);
    return repository.message(id);
  }

  public Message retry(String id, String requestId) {
    var message = repository.message(id);
    if (!message.role().equals("assistant") || message.status().equals("RUNNING"))
      throw new ApiException(409, "RUN_ACTIVE", "该消息不能重试");
    return start(message.conversationId(), new Ask(requestId,message.mode(),message.question(),message.pages(),message.chapterId()), true);
  }

  @PreDestroy public void shutdown() {
    polling.shutdownNow(); subscriptions.forEach(subscription -> subscription.emitter.complete()); subscriptions.clear();
  }
}
