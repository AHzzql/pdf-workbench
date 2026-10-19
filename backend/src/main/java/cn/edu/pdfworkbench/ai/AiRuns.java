package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AiRuns {
  private final AiRepository repository;
  private final AiReading reading;
  private final AiModelClient models;
  private final Map<String, Run> active = new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
  private static final Set<String> MODES =
      Set.of("AUTO", "WELCOME", "ASK", "BRIEF", "DETAILED", "CHAPTER");

  private static class Run {
    Message message;
    String stage = "等待处理";
    Future<?> task;
    boolean done;
    long saved;
    String owner;
    java.util.function.BooleanSupplier access;
    final List<Listener> listeners = new CopyOnWriteArrayList<>();

    Run(Message message) {
      this.message = message;
    }
  }

  private record Listener(SseEmitter emitter, java.util.function.BooleanSupplier access) {}

  private String key(String id) {
    return repository.owner() + "/" + id;
  }

  public AiRuns(AiRepository repository, AiReading reading, AiModelClient models) {
    this.repository = repository;
    this.reading = reading;
    this.models = models;
    heartbeat.scheduleAtFixedRate(
        () ->
            active
                .values()
                .forEach(
                    r -> {
                      synchronized (r) {
                        emit(r);
                      }
                    }),
        15,
        15,
        TimeUnit.SECONDS);
  }

  public synchronized Message start(String conversationId, Ask ask, boolean retry) {
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
    if (active.size() >= 2 || busy(conversationId))
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
    var run = new Run(message);
    run.owner = repository.owner();
    run.access = repository.access();
    active.put(key(message.id()), run);
    run.task = workers.submit(() -> execute(run, conversation));
    return message;
  }

  private void execute(Run run, Conversation conversation) {
    try (var scope = repository.scope(run.owner, run.access)) {
      executeScoped(run, conversation);
    }
  }

  private void executeScoped(Run run, Conversation conversation) {
    try {
      var citations =
          reading.generate(
              conversation,
              run.message,
              stage -> {
                synchronized (run) {
                  ensureActive(run);
                  run.stage = stage;
                  emit(run);
                }
              },
              text -> {
                synchronized (run) {
                  ensureActive(run);
                  if (run.message.text().length() + text.length() > 50000)
                    throw new ApiException(413, "AI_OUTPUT_LIMIT", "回答超过长度限制");
                  update(run, run.message.text() + text, "RUNNING", "", List.of());
                }
              });
      synchronized (run) {
        if (!run.done) {
          String text = AiReading.validateReferences(run.message.text(), citations);
          if (text.isBlank()) throw new ApiException(502, "EMPTY_ANSWER", "模型未返回有效回答，请重试");
          finish(run, "COMPLETED", "", text, AiReading.usedCitations(text, citations));
        }
      }
    } catch (Exception e) {
      synchronized (run) {
        if (!run.done)
          finish(
              run,
              Thread.currentThread().isInterrupted() ? "STOPPED" : "FAILED",
              e instanceof ApiException a ? a.getMessage() : "处理未完成，请稍后重试",
              run.message.text(),
              List.of());
      }
    } finally {
      active.remove(run.owner + "/" + run.message.id(), run);
    }
  }

  private void ensureActive(Run run) {
    if (run.done || Thread.currentThread().isInterrupted()) throw new CancellationException();
  }

  private void update(Run r, String text, String status, String error, List<Citation> citations) {
    var m = r.message;
    r.message =
        new Message(
            m.id(),
            m.conversationId(),
            m.role(),
            text,
            status,
            error,
            m.mode(),
            m.question(),
            m.pages(),
            m.chapterId(),
            citations,
            m.createdAt(),
            m.sequence() + 1);
    if (!status.equals("RUNNING") || System.currentTimeMillis() - r.saved >= 250) {
      repository.save(r.message);
      r.saved = System.currentTimeMillis();
    }
    emit(r);
  }

  private void finish(Run r, String status, String error, String text, List<Citation> citations) {
    r.done = true;
    r.stage = status.equals("COMPLETED") ? "已完成" : status.equals("STOPPED") ? "已停止" : "生成失败";
    update(r, text, status, error, citations);
    r.listeners.forEach(listener -> listener.emitter().complete());
    r.listeners.clear();
  }

  private void emit(Run r) {
    for (var listener : r.listeners) {
      var emitter = listener.emitter();
      try {
        if (!listener.access().getAsBoolean()) {
          r.listeners.remove(listener);
          emitter.complete();
          continue;
        }
        emitter.send(
            SseEmitter.event()
                .name("snapshot")
                .id(Long.toString(r.message.sequence()))
                .data(Map.of("message", r.message, "stage", r.stage)));
      } catch (IOException | IllegalStateException e) {
        r.listeners.remove(listener);
        emitter.complete();
      }
    }
  }

  public boolean busy(String conversationId) {
    return active.values().stream()
        .anyMatch(
            r ->
                r.owner.equals(repository.owner())
                    && r.message.conversationId().equals(conversationId));
  }

  public Message snapshot(String id) {
    var run = active.get(key(id));
    if (run == null) return repository.message(id);
    synchronized (run) {
      return run.message;
    }
  }

  public SseEmitter events(String id) throws IOException {
    repository.message(id);
    var emitter = new SseEmitter(0L);
    var listener = new Listener(emitter, repository.access());
    var run = active.get(key(id));
    if (run == null) {
      var message = repository.message(id);
      emitter.send(
          SseEmitter.event()
              .name("snapshot")
              .data(Map.of("message", message, "stage", message.status())));
      emitter.complete();
    } else
      synchronized (run) {
        emitter.onCompletion(() -> run.listeners.remove(listener));
        emitter.onTimeout(() -> run.listeners.remove(listener));
        emitter.onError(e -> run.listeners.remove(listener));
        run.listeners.add(listener);
        emit(run);
        if (run.done) emitter.complete();
      }
    return emitter;
  }

  public synchronized Message cancel(String id) {
    var run = active.get(key(id));
    if (run == null) return repository.message(id);
    synchronized (run) {
      if (!run.done) finish(run, "STOPPED", "", run.message.text(), List.of());
      if (run.task != null) run.task.cancel(true);
      active.remove(key(id), run);
      return run.message;
    }
  }

  public Message retry(String id, String requestId) {
    var m = repository.message(id);
    if (!m.role().equals("assistant") || m.status().equals("RUNNING"))
      throw new ApiException(409, "RUN_ACTIVE", "该消息不能重试");
    return start(
        m.conversationId(),
        new Ask(requestId, m.mode(), m.question(), m.pages(), m.chapterId()),
        true);
  }

  @PreDestroy
  public void shutdown() {
    for (var run : List.copyOf(active.values())) {
      try (var scope = repository.scope(run.owner, run.access)) {
        cancel(run.message.id());
      }
    }
    heartbeat.shutdownNow();
    workers.shutdownNow();
  }
}
