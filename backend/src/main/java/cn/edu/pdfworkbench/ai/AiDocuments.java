package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.exception.ApiException;
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
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public AiDocuments(AiRepository repository, AiSettings settings, AiRetrieval retrieval) {
    this.repository = repository;
    this.settings = settings;
    this.retrieval = retrieval;
  }

  public synchronized Document register(MultipartFile file, String password) throws Exception {
    if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024)
      throw new ApiException(413, "FILE_TOO_LARGE", "PDF 文件必须为 1 字节至 50 MiB");
    var known = repository.documents();
    Path temp = Files.createTempFile(repository.root(), "upload-", ".tmp");
    String id = UUID.randomUUID().toString();
    boolean saved = false;
    try {
      file.transferTo(temp);
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
            && Files.exists(repository.documentPath(d.id()))
            && Files.exists(repository.contentPath(d.id()))) return d;
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
      Files.move(temp, repository.documentPath(id));
      repository.content(id, content);
      repository.save(document);
      saved = true;
      return document;
    } finally {
      Files.deleteIfExists(temp);
      if (!saved) {
        Files.deleteIfExists(repository.documentPath(id));
        Files.deleteIfExists(repository.contentPath(id));
      }
    }
  }

  public Content prepare(String id, Consumer<String> progress) throws Exception {
    var lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
    lock.lockInterruptibly();
    try {
      var d = repository.document(id);
      if (d.status().equals("DELETING")) throw new ApiException(409, "DOCUMENT_DELETING", "文档正在清理");
      var content = repository.content(id);
      if (!d.status().equals("READY") || !Files.exists(repository.indexPath(id))) {
        progress.accept("正在准备检索索引");
        retrieval.index(id, content.chunks(), progress);
        repository.save(
            new Document(
                d.id(),
                d.name(),
                d.hash(),
                d.version(),
                d.pages(),
                d.encrypted(),
                "READY",
                "",
                d.createdAt()));
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
    repository.save(c);
    return c;
  }

  public synchronized void delete(String id) throws IOException {
    var lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
    if (!lock.tryLock()) throw new ApiException(409, "DOCUMENT_BUSY", "文档正在处理");
    try {
      var d = repository.document(id);
      if (repository.conversations().stream().anyMatch(c -> c.documentId().equals(id)))
        throw new ApiException(409, "DOCUMENT_IN_USE", "文档仍有关联会话");
      repository.save(
          new Document(
              d.id(),
              d.name(),
              d.hash(),
              d.version(),
              d.pages(),
              d.encrypted(),
              "DELETING",
              "",
              d.createdAt()));
      Path index = repository.indexPath(id);
      if (Files.exists(index))
        try (var walk = Files.walk(index)) {
          for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
      Files.deleteIfExists(repository.documentPath(id));
      Files.deleteIfExists(repository.contentPath(id));
      repository.deleteDocumentRecord(id);
    } finally {
      lock.unlock();
    }
  }

  public synchronized void deleteConversation(String id, boolean cleanDocument) throws IOException {
    var conversation = repository.conversation(id);
    var lock = locks.computeIfAbsent(conversation.documentId(), k -> new ReentrantLock());
    if (!lock.tryLock()) throw new ApiException(409, "DOCUMENT_BUSY", "文档正在处理，请稍后重试");
    try {
      repository.deleteConversation(id);
      if (cleanDocument
          && repository.conversations().stream()
              .noneMatch(c -> c.documentId().equals(conversation.documentId())))
        delete(conversation.documentId());
    } finally {
      lock.unlock();
    }
  }
}
