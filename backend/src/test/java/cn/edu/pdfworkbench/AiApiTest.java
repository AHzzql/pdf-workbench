package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.pdfworkbench.ai.*;
import cn.edu.pdfworkbench.ai.AiTypes.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AiApiTest {
  static final TestServices SERVICES = new TestServices();

  @org.junit.jupiter.api.AfterAll
  static void closeServices() { SERVICES.close(); }

  static final Path ROOT;

  static {
    try {
      ROOT = Files.createTempDirectory("pdf-ai-api-");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    SERVICES.register(r);
    r.add("ai.data-directory", ROOT::toString);
    r.add("ai.api-key", () -> "");
  }

  @Autowired TestRestTemplate http;
  @Autowired AiRepository repository;
  @Autowired AiRuns runs;
  @Autowired ElasticsearchIndex searchIndex;
  @MockitoBean AiModelClient models;
  final AtomicBoolean slow = new AtomicBoolean();
  final String base = "/api/v1/pdf/ai";

  AiRepository.Scope workspace;

  @BeforeEach
  void models() throws Exception {
    var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
    var csrf = new java.util.HashMap<String, String>();
    http.getRestTemplate().setInterceptors(List.of((request, body, execution) -> {
      cookies.get(request.getURI(), Map.of()).forEach((name, values) -> request.getHeaders().put(name, values));
      if (!Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod().name()))
        csrf.forEach((name, value) -> request.getHeaders().set(name, value));
      var response = execution.execute(request, body);
      cookies.put(request.getURI(), response.getHeaders());
      return response;
    }));
    var session = http.getForObject("/api/v1/account/session", cn.edu.pdfworkbench.auth.AccountTypes.Session.class);
    workspace = repository.scope(session.workspaceId(), () -> true);
    var token = http.getForObject("/api/v1/account/csrf", com.fasterxml.jackson.databind.JsonNode.class);
    csrf.put(token.get("headerName").asText(), token.get("token").asText());
    slow.set(false);
    when(models.embed(anyList()))
        .thenAnswer(
            call ->
                ((List<String>) call.getArgument(0))
                    .stream()
                        .map(
                            s -> {
                              float[] v = new float[1024];
                              v[0] = 1;
                              v[1] = s.contains("Alpha") ? 1 : 0.1f;
                              return v;
                            })
                        .toList());
    when(models.rerank(anyString(), anyList()))
        .thenAnswer(
            call -> {
              List<String> list = call.getArgument(1);
              return java.util.stream.IntStream.range(0, Math.min(8, list.size())).boxed().toList();
            });
    when(models.complete(anyString(), anyString())).thenReturn("Alpha evidence [p1-0]");
    doAnswer(
        call -> {
          Consumer<String> output = call.getArgument(2);
          output.accept("Alpha is 42. ");
          if (slow.get()) Thread.sleep(30000);
          output.accept("[p1-0] [p999-0]");
          return null;
        })
        .when(models)
        .stream(anyString(), anyString(), any());
  }

  byte[] pdf(String content) throws Exception {
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      pdf.addPage(page);
      try (var stream = new PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(60, 700);
        stream.showText(content);
        stream.endText();
      }
      pdf.save(out);
      return out.toByteArray();
    }
  }

  Document upload(byte[] pdf) {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add(
        "file",
        new ByteArrayResource(pdf) {
          @Override
          public String getFilename() {
            return "reading.pdf";
          }
        });
    var response = http.postForEntity(base + "/documents", body, Document.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  Conversation conversation(Document d) {
    return http.postForObject(
        base + "/conversations", new CreateConversation(d.id()), Conversation.class);
  }

  Message ask(Conversation c, String mode, String pages) {
    var response =
        http.postForEntity(
            base + "/conversations/" + c.id() + "/messages",
            new Ask(UUID.randomUUID().toString(), mode, "What is Alpha?", pages, null),
            Message.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  Message await(String id) throws Exception {
    long deadline = System.currentTimeMillis() + 20000;
    Message m;
    do {
      m = http.getForObject(base + "/runs/" + id, Message.class);
      if (!m.status().equals("RUNNING")) return m;
      Thread.sleep(30);
    } while (System.currentTimeMillis() < deadline);
    fail("Generation did not terminate");
    return m;
  }

  @Test
  void welcomeIsAssistantOnlyAndCannotBeDuplicated() throws Exception {
    var c = conversation(upload(pdf("Alpha is 42.")));
    var request = new Ask(UUID.randomUUID().toString(), "WELCOME", "", null, null);
    var initial =
        http.postForEntity(base + "/conversations/" + c.id() + "/messages", request, Message.class);
    assertEquals(HttpStatus.OK, initial.getStatusCode());
    assertEquals("COMPLETED", await(initial.getBody().id()).status());
    assertEquals(1, repository.messages(c.id()).size());
    assertEquals("assistant", repository.messages(c.id()).getFirst().role());
    assertEquals("新对话", repository.conversation(c.id()).title());
    assertEquals(
        HttpStatus.OK,
        http.postForEntity(base + "/conversations/" + c.id() + "/messages", request, Message.class)
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(
                base + "/conversations/" + c.id() + "/messages",
                new Ask(UUID.randomUUID().toString(), "WELCOME", "", null, null),
                String.class)
            .getStatusCode());
  }

  @Test
  void automaticSummaryUsesFullEvidenceWithoutSearch() throws Exception {
    when(models.complete(anyString(), anyString()))
        .thenReturn(
            "{\"mode\":\"DETAILED\",\"pages\":\"\",\"chapterId\":\"\",\"clarification\":\"\"}");
    var c = conversation(upload(pdf("Alpha is 42.")));
    var request = new Ask(UUID.randomUUID().toString(), "AUTO", "详细总结全文", null, null);
    var m =
        http.postForObject(base + "/conversations/" + c.id() + "/messages", request, Message.class);
    assertEquals("COMPLETED", await(m.id()).status());
    verify(models, never()).rerank(anyString(), anyList());
    verify(models).stream(anyString(), contains("生成详细摘要"), any());
    assertEquals("详细总结全文", repository.messages(c.id()).getFirst().text());
  }

  @Test
  void invalidAutomaticScopeClarifiesWithoutAnswerGeneration() throws Exception {
    when(models.complete(anyString(), anyString()))
        .thenReturn(
            "{\"mode\":\"CHAPTER\",\"pages\":\"3-8\",\"chapterId\":\"\",\"clarification\":\"\"}");
    var c = conversation(upload(pdf("Alpha is 42.")));
    var request = new Ask(UUID.randomUUID().toString(), "AUTO", "分析第 3-8 页", null, null);
    var m =
        http.postForObject(base + "/conversations/" + c.id() + "/messages", request, Message.class);
    var answer = await(m.id());
    assertEquals("COMPLETED", answer.status());
    assertTrue(answer.text().contains("共 1 页"));
    assertTrue(answer.citations().isEmpty());
    verify(models, never()).stream(anyString(), anyString(), any());
    verify(models, never()).rerank(anyString(), anyList());
  }

  @Test
  void autoRejectsEmptyQuestionAndHiddenExplicitScope() throws Exception {
    var c = conversation(upload(pdf("Alpha is 42.")));
    for (var request :
        List.of(
            new Ask(UUID.randomUUID().toString(), "AUTO", "", null, null),
            new Ask(UUID.randomUUID().toString(), "AUTO", "全文摘要", "1", null))) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          http.postForEntity(base + "/conversations/" + c.id() + "/messages", request, String.class)
              .getStatusCode());
    }
    assertTrue(repository.messages(c.id()).isEmpty());
  }

  @Test
  void completeFlowUsesRealIndexAndValidatedCitation() throws Exception {
    var d = upload(pdf("Alpha is 42."));
    var c = conversation(d);
    var m = await(ask(c, "ASK", null).id());
    assertEquals("COMPLETED", m.status());
    assertEquals(1, m.citations().size());
    assertEquals(1, m.citations().getFirst().page());
    assertFalse(m.citations().getFirst().bounds().isEmpty());
    assertEquals("Alpha is 42.", m.citations().getFirst().quote().strip());
    assertFalse(m.text().contains("[p999-0]"));
    verify(models).rerank(anyString(), anyList());
    String events = http.getForObject(base + "/runs/" + m.id() + "/events", String.class);
    assertTrue(events.contains("event:snapshot"));
    assertTrue(events.contains("COMPLETED"));
    assertArrayEquals(
        Files.readAllBytes(repository.documentPath(d.id())),
        http.getForObject(base + "/documents/" + d.id() + "/file", byte[].class));
  }

  @Test
  void duplicateImportReusesDocumentButNotDifferentContent() throws Exception {
    byte[] source = pdf("Duplicate sample Alpha");
    var a = upload(source);
    var b = upload(source);
    var c = upload(pdf("Different content"));
    assertEquals(a.id(), b.id());
    assertNotEquals(a.id(), c.id());
  }

  @Test
  void historyRenameAndDeletionCleanAssociatedFiles() throws Exception {
    var d = upload(pdf("Delete sample"));
    var c = conversation(d);
    await(ask(c, "BRIEF", null).id());
    String generation = repository.indexGeneration(d.id());
    assertNotNull(generation);
    assertTrue(searchIndex.ready(repository.owner(), d.id(), generation, d.version(), 1));
    var response =
        http.exchange(
            base + "/conversations/" + c.id(),
            HttpMethod.PATCH,
            new HttpEntity<>(new Rename("研究摘要")),
            Conversation.class);
    assertEquals("研究摘要", response.getBody().title());
    var list =
        http.postForObject(
            base + "/conversations/search", new HistoryQuery("研究摘要"), Conversation[].class);
    assertEquals(1, list.length);
    http.exchange(
        base + "/conversations/" + c.id(),
        HttpMethod.DELETE,
        new HttpEntity<>(new DeleteConversation(true)),
        Void.class);
    assertFalse(Files.exists(repository.documentPath(d.id())));
    assertFalse(Files.exists(repository.indexPath(d.id())));
    assertFalse(searchIndex.ready(repository.owner(), d.id(), generation, d.version(), 1));
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity(base + "/conversations/" + c.id() + "/messages", String.class)
            .getStatusCode());
  }

  @Test
  void sharedDocumentSurvivesDeletingOneConversation() throws Exception {
    var d = upload(pdf("Shared sample"));
    var a = conversation(d);
    conversation(d);
    http.exchange(
        base + "/conversations/" + a.id(),
        HttpMethod.DELETE,
        new HttpEntity<>(new DeleteConversation(true)),
        Void.class);
    assertTrue(Files.exists(repository.documentPath(d.id())));
    assertEquals(
        HttpStatus.CONFLICT,
        http.exchange(
                base + "/documents/" + d.id(), HttpMethod.DELETE, HttpEntity.EMPTY, String.class)
            .getStatusCode());
  }

  @Test
  void stoppedGenerationCanRetryWithoutDuplicatingQuestion() throws Exception {
    slow.set(true);
    var c = conversation(upload(pdf("Cancel Alpha")));
    var m = ask(c, "ASK", null);
    assertEquals(
        HttpStatus.CONFLICT,
        http.exchange(
                base + "/conversations/" + c.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(new DeleteConversation(true)),
                String.class)
            .getStatusCode());
    var stopped = http.postForObject(base + "/runs/" + m.id() + "/cancel", null, Message.class);
    assertEquals("STOPPED", stopped.status());
    slow.set(false);
    var retry =
        http.postForObject(
            base + "/messages/" + m.id() + "/retry",
            Map.of("requestId", UUID.randomUUID().toString()),
            Message.class);
    assertEquals("COMPLETED", await(retry.id()).status());
    assertEquals(
        1, repository.messages(c.id()).stream().filter(v -> v.role().equals("user")).count());
  }

  @Test
  void repeatedRequestIsIdempotentAndConflictsAreRejected() throws Exception {
    var c = conversation(upload(pdf("Idempotent Alpha")));
    var request = new Ask(UUID.randomUUID().toString(), "ASK", "Alpha?", null, null);
    var first =
        http.postForObject(base + "/conversations/" + c.id() + "/messages", request, Message.class);
    var next =
        http.postForObject(base + "/conversations/" + c.id() + "/messages", request, Message.class);
    assertEquals(first.id(), next.id());
    await(first.id());
    var conflict = new Ask(request.requestId(), "ASK", "Other?", null, null);
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(base + "/conversations/" + c.id() + "/messages", conflict, String.class)
            .getStatusCode());
  }

  @Test
  void badScopeAndHostileOriginAreRejected() throws Exception {
    var c = conversation(upload(pdf("Scopes Alpha")));
    assertEquals(
        HttpStatus.BAD_REQUEST,
        http.postForEntity(
                base + "/conversations/" + c.id() + "/messages",
                new Ask(UUID.randomUUID().toString(), "ASK", "Alpha", "9", null),
                String.class)
            .getStatusCode());
    var headers = new HttpHeaders();
    headers.setOrigin("https://untrusted.example");
    assertEquals(
        HttpStatus.FORBIDDEN,
        http.exchange(base + "/status", HttpMethod.GET, new HttpEntity<>(headers), String.class)
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        http.getForEntity(base + "/status?key=not-a-secret", String.class).getStatusCode());
  }

  @Test
  void blankPdfAndMalformedBodyAreRejected() throws Exception {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add(
        "file",
        new ByteArrayResource(pdf(" ")) {
          @Override
          public String getFilename() {
            return "empty.pdf";
          }
        });
    assertEquals(
        HttpStatus.UNPROCESSABLE_ENTITY,
        http.postForEntity(base + "/documents", body, String.class).getStatusCode());
    var h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    assertEquals(
        HttpStatus.BAD_REQUEST,
        http.postForEntity(base + "/conversations", new HttpEntity<>("{", h), String.class)
            .getStatusCode());
  }

  @Test
  void providerFailureIsSavedAndRetryable() throws Exception {
    doThrow(new cn.edu.pdfworkbench.exception.ApiException(502, "AI_PROVIDER_ERROR", "模型调用失败"))
        .when(models)
        .stream(anyString(), anyString(), any());
    var c = conversation(upload(pdf("Provider fail Alpha")));
    var m = await(ask(c, "ASK", null).id());
    assertEquals("FAILED", m.status());
    assertFalse(m.error().contains("sk-"));
  }

  @Test
  void swaggerIncludesJsonAndStreamingContracts() throws Exception {
    String spec = http.getForObject("/v3/api-docs", String.class);
    assertTrue(spec.contains("/api/v1/pdf/ai/runs/{id}/events"));
    assertTrue(spec.contains("text/event-stream"));
    assertTrue(spec.contains("application/json"));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(spec);
    var upload = json.path("paths").path(base + "/documents").path("post");
    assertEquals(0, upload.path("parameters").size());
    var schema =
        upload.path("requestBody").path("content").path("multipart/form-data").path("schema");
    assertTrue(schema.path("properties").has("file"));
    assertTrue(schema.path("properties").has("password"));
    assertEquals(
        "binary",
        json.path("paths")
            .path(base + "/documents/{id}/file")
            .path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path("application/pdf")
            .path("schema")
            .path("format")
            .asText());
    assertTrue(
        upload
            .path("responses")
            .path("403")
            .path("content")
            .path("application/json")
            .path("schema")
            .path("properties")
            .has("code"));
  }

  @Test
  void encryptedImportRequiresPasswordAndExtractionPermission() throws Exception {
    byte[] encrypted;
    try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf("Encrypted Alpha"));
        var out = new ByteArrayOutputStream()) {
      var permission = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
      permission.setCanExtractContent(false);
      var policy =
          new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
              "owner-secret", "reader-secret", permission);
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      doc.save(out);
      encrypted = out.toByteArray();
    }
    var body = new LinkedMultiValueMap<String, Object>();
    body.add(
        "file",
        new ByteArrayResource(encrypted) {
          @Override
          public String getFilename() {
            return "encrypted.pdf";
          }
        });
    body.add("password", "wrong");
    assertEquals(
        HttpStatus.FORBIDDEN,
        http.postForEntity(base + "/documents", body, String.class).getStatusCode());
    body.set("password", "reader-secret");
    assertEquals(
        HttpStatus.FORBIDDEN,
        http.postForEntity(base + "/documents", body, String.class).getStatusCode());
    body.set("password", "owner-secret");
    var accepted = http.postForEntity(base + "/documents", body, Document.class);
    assertEquals(HttpStatus.OK, accepted.getStatusCode());
    assertTrue(accepted.getBody().encrypted());
    assertFalse(
        Files.readString(repository.contentPath(accepted.getBody().id())).contains("owner-secret"));
    body.set("password", "wrong");
    assertEquals(
        HttpStatus.FORBIDDEN,
        http.postForEntity(base + "/documents", body, String.class).getStatusCode());
  }

  @Test
  void emptyOutputIsFailureInsteadOfCompleted() throws Exception {
    doNothing().when(models).stream(anyString(), anyString(), any());
    var c = conversation(upload(pdf("Empty output")));
    assertEquals("FAILED", await(ask(c, "BRIEF", null).id()).status());
  }

  @Test
  void nullModeAndUnscopedChapterAreBadRequests() throws Exception {
    var c = conversation(upload(pdf("Invalid request")));
    for (String mode : Arrays.asList(null, "CHAPTER", "UNKNOWN")) {
      var response =
          http.postForEntity(
              base + "/conversations/" + c.id() + "/messages",
              new Ask(UUID.randomUUID().toString(), mode, "question", null, null),
              String.class);
      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
  }

  @Test
  void requestIdsCannotOverwriteAnotherConversation() throws Exception {
    var d = upload(pdf("Request collision"));
    var a = conversation(d);
    var b = conversation(d);
    var first = await(ask(a, "BRIEF", null).id());
    var conflict = new Ask(first.id(), "BRIEF", "What is Alpha?", null, null);
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(base + "/conversations/" + b.id() + "/messages", conflict, String.class)
            .getStatusCode());
    assertEquals(a.id(), repository.message(first.id()).conversationId());
  }

  @RepeatedTest(3)
  void concurrentRunsAreBoundedAndCancellationReleasesCapacity() throws Exception {
    slow.set(true);
    var d = upload(pdf("Concurrent Alpha"));
    var a = conversation(d);
    var b = conversation(d);
    var c = conversation(d);
    var first = ask(a, "BRIEF", null);
    var second = ask(b, "BRIEF", null);
    var request = new Ask(UUID.randomUUID().toString(), "BRIEF", "", null, null);
    assertEquals(
        HttpStatus.TOO_MANY_REQUESTS,
        http.postForEntity(base + "/conversations/" + c.id() + "/messages", request, String.class)
            .getStatusCode());
    runs.cancel(first.id());
    runs.cancel(second.id());
    slow.set(false);
    assertEquals(
        "COMPLETED",
        await(
                http.postForObject(
                        base + "/conversations/" + c.id() + "/messages", request, Message.class)
                    .id())
            .status());
  }

  @Test
  void deletionTombstoneBlocksDownloadsChaptersAndNewConversations() throws Exception {
    var d = upload(pdf("tombstone source"));
    repository.markDeleting(d.id());
    for (String path : List.of("/documents/" + d.id() + "/file", "/documents/" + d.id() + "/chapters"))
      assertEquals(HttpStatus.CONFLICT, http.getForEntity(base + path, String.class).getStatusCode());
    assertEquals(HttpStatus.CONFLICT, http.postForEntity(base + "/conversations", new CreateConversation(d.id()), String.class).getStatusCode());
    assertEquals(HttpStatus.OK, http.exchange(base + "/documents/" + d.id(), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, http.getForEntity(base + "/documents/" + d.id(), String.class).getStatusCode());
  }

  @AfterEach
  void stopRemaining() {
    try {
      if (workspace != null)
        for (var c : repository.conversations())
          for (var m : repository.messages(c.id()))
            if (m.status().equals("RUNNING")) runs.cancel(m.id());
    } finally {
      if (workspace != null) workspace.close();
      http.getRestTemplate().setInterceptors(List.of());
    }
  }
}
