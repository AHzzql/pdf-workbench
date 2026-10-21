package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;
import cn.edu.pdfworkbench.exception.ApiException;
import cn.edu.pdfworkbench.tasks.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiTaskHandler implements TaskHandler {
  private final AiRepository repository;
  private final AiDocuments documents;
  private final AiRetrieval retrieval;
  private final AiReading reading;
  private final AiSettings settings;
  private final TaskStore tasks;
  private final cn.edu.pdfworkbench.storage.DocumentStorage files;

  public AiTaskHandler(AiRepository repository, AiDocuments documents, AiRetrieval retrieval, AiReading reading, AiSettings settings, TaskStore tasks,
      cn.edu.pdfworkbench.storage.DocumentStorage files) {
    this.repository=repository; this.documents=documents; this.retrieval=retrieval; this.reading=reading; this.settings=settings; this.tasks=tasks;
    this.files=files;
  }

  @Override public Runnable execute(TaskStore.Task task) throws Exception {
    return switch (task.kind()) {
      case GENERATE -> generate(task);
      case INDEX -> {
        var document = repository.document(task.document());
        if (document.status().equals("DELETING")) throw new ApiException(409,"DOCUMENT_DELETING","文档正在清理");
        var content = repository.content(task.document());
        if (!retrieval.ready(task.document(), content.chunks().size()))
          retrieval.index(task.document(), content.chunks(), stage -> tasks.stage(task,stage),
              token -> tasks.fenced(task, () -> repository.completeIndex(task.document(),token,settings.indexVersion())));
        yield () -> {};
      }
      case CLEAN -> {
        files.retire(task.workspace(),task.document());
        documents.cleanFiles(task.document());
        files.cleanDocument(task.workspace(),task.document());
        yield () -> repository.deleteDocumentRecord(task.document());
      }
    };
  }

  private Message updated(Message original, String text, String status, List<Citation> citations) {
    var current = repository.message(original.id());
    return new Message(original.id(),original.conversationId(),original.role(),text,status,"",original.mode(),original.question(),
        original.pages(),original.chapterId(),citations,original.createdAt(),current.sequence()+1);
  }

  private Runnable generate(TaskStore.Task task) throws Exception {
    var message = repository.message(task.target());
    var conversation = repository.conversation(message.conversationId());
    var text = new StringBuilder();
    long[] saved = {0};
    var citations = reading.generate(conversation,message,stage -> tasks.stage(task,stage), delta -> {
      if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
      if (text.length()+delta.length() > 50000) throw new ApiException(413,"AI_OUTPUT_LIMIT","回答超过长度限制");
      text.append(delta);
      if (System.currentTimeMillis()-saved[0] >= 250) {
        // Reactive provider callbacks run on a different thread from the consumer.
        try (var scope=repository.scope(task.workspace(),()->tasks.active(task))) {
          tasks.fenced(task, () -> { repository.save(updated(message,text.toString(),"RUNNING",List.of())); return null; });
        }
        saved[0]=System.currentTimeMillis();
      }
    });
    String validated = AiReading.validateReferences(text.toString(), citations);
    if (validated.isBlank()) throw new ApiException(502,"EMPTY_ANSWER","模型未返回有效回答，请重试");
    var used = AiReading.usedCitations(validated,citations);
    return () -> repository.save(updated(message,validated,"COMPLETED",used));
  }
}
