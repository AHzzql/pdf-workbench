package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.tasks.TaskStore;
import cn.edu.pdfworkbench.model.TextOptions;
import cn.edu.pdfworkbench.util.PdfInfoUtils;
import cn.edu.pdfworkbench.util.PdfTextUtils;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AiDocuments {
  private final AiRepository repository;
  private final AiSettings settings;
  private final AiRetrieval retrieval;
  private final TaskStore tasks;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public AiDocuments(AiRepository repository, AiSettings settings, AiRetrieval retrieval, TaskStore tasks) {
    this.repository = repository;
    this.settings = settings;
    this.retrieval = retrieval;
    this.tasks = tasks;
  }

  public synchronized Document register(MultipartFile file, String password) throws Exception {
    if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024)
      throw new ApiException(413, "FILE_TOO_LARGE", "PDF 文件必须为 1 字节至 50 MiB");
    var known = repository.documents();
    Files.createDirectories(repository.root());
    Path temp = Files.createTempFile(repository.root(), "upload-", ".tmp");
    String id = UUID.randomUUID().toString();
    try {
      file.transferTo(temp);
      if (Files.size(temp)<1 || Files.size(temp)>50L*1024*1024)
        throw new ApiException(413,"FILE_TOO_LARGE","PDF 文件必须为 1 字节至 50 MiB");
      String hash;
      try (var input = Files.newInputStream(temp)) {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] b = new byte[8192];
        for (int n; (n = input.read(b)) != -1; ) digest.update(b, 0, n);
        hash = HexFormat.of().formatHex(digest.digest());
      }
      int pages;
      boolean encrypted;
      try (var pdf = Loader.loadPDF(temp.toFile(), password == null ? "" : password)) {
        pages = pdf.getNumberOfPages();
        encrypted = pdf.isEncrypted();
        if (pages < 1 || pages > 200)
          throw new ApiException(413, "PAGE_LIMIT", "AI 阅读支持 1 至 200 页 PDF");
      }
      var text = PdfTextUtils.extract(temp, password, new TextOptions(null, true));
      if (text.pages().stream().mapToLong(p -> p.text().length()).sum() > 2_000_000)
        throw new ApiException(413, "TEXT_LIMIT", "文档文字量超过 AI 处理限制");
      for (var d : known)
        if (d.hash().equals(hash)
            && d.version().equals(settings.indexVersion())
            && !d.status().equals("DELETING")
            && repository.filesReady(d.id())) return d;
      var content = AiChunker.split(text, PdfInfoUtils.read(temp, password).bookmarks());
      if (content.chunks().isEmpty())
        throw new ApiException(422, "NO_TEXT", "文档没有可提取文字，当前不支持扫描件 OCR");
      if (content.chunks().size() > 4000) throw new ApiException(413, "CHUNK_LIMIT", "文档片段数量超过限制");
      String name =
          Objects.toString(file.getOriginalFilename(), "document.pdf")
              .replaceAll("[\\\\/\\p{Cntrl}]", "_");
      if (name.length() > 160) name = name.substring(name.length() - 160);
      var document =
          new Document(
              id,
              name,
              hash,
              settings.indexVersion(),
              pages,
              encrypted,
              "PARSED",
              "",
              System.currentTimeMillis());
      repository.publishDocument(document,temp,content);
      return document;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public Content prepare(String id, Consumer<String> progress) throws Exception {
    var lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
    lock.lockInterruptibly();
    try {
      var d = repository.document(id);
      if (d.status().equals("DELETING")) throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
      var content = repository.content(id);
      if (!retrieval.ready(id, content.chunks().size())) {
        progress.accept("等待检索索引");
        var task = tasks.enqueue(repository.owner(), TaskStore.Kind.INDEX, id, id, null);
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
        while (!retrieval.ready(id, content.chunks().size())) {
          if (Thread.currentThread().isInterrupted() || !repository.access().getAsBoolean()) throw new java.util.concurrent.CancellationException();
          var current = tasks.get(task.id());
          if (current == null || current.terminal()) throw new ApiException(503, "SEARCH_INDEX_FAILED", "文档索引未能完成，请稍后重试");
          if (System.nanoTime() > deadline) throw new ApiException(503, "SEARCH_INDEX_TIMEOUT", "文档索引等待超时，请稍后重试");
          Thread.sleep(100);
        }
      }
      return content;
    } finally {
      lock.unlock();
    }
  }

  public synchronized Conversation createConversation(String id) {
    var d = repository.document(id);
    if (d.status().equals("DELETING")) throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
    long now = System.currentTimeMillis();
    var c = new Conversation(UUID.randomUUID().toString(), id, "新对话", now, now);
    return repository.createConversation(c);
  }

  public void delete(String id) throws IOException {
    tasks.submission(() -> {
      tasks.cancelDocument(repository.owner(), id);
      repository.markDeleting(id);
      tasks.enqueue(repository.owner(), TaskStore.Kind.CLEAN, id, id, null);
      return null;
    });
  }

  public void cleanFiles(String id) throws IOException {
    try {
      if (!repository.document(id).status().equals("DELETING")) throw new ApiException(409, "DOCUMENT_NOT_DELETING", "文档尚未进入清理状态");
    } catch (ApiException e) { if (e.status() == 404) return; throw e; }
    retrieval.delete(id);
    Path index = repository.legacyIndexPath(id);
    if (Files.exists(index))
      try (var walk = Files.walk(index)) {
        for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    Files.deleteIfExists(repository.legacyDocumentPath(id));
    Files.deleteIfExists(repository.legacyContentPath(id));
  }

  public void deleteConversation(String id, boolean cleanDocument) throws IOException {
    tasks.submission(() -> {
      var conversation = repository.conversation(id);
      if (tasks.busy(repository.owner(), id)) throw new ApiException(409, "RUN_ACTIVE", "请先停止当前会话的生成");
      repository.deleteConversation(id);
      if (cleanDocument && repository.conversations().stream().noneMatch(c -> c.documentId().equals(conversation.documentId()))) {
        try { delete(conversation.documentId()); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
      }
      return null;
    });
  }
}
