package cn.edu.pdfworkbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.pdfworkbench.ai.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AccountApiTest {
  static final TestServices SERVICES = new TestServices();

  @org.junit.jupiter.api.AfterAll
  static void closeServices(@org.springframework.beans.factory.annotation.Autowired cn.edu.pdfworkbench.tasks.TaskRuntime runtime) { runtime.stop(); SERVICES.close(); }

  static final Path ROOT;

  static {
    try {
      ROOT = Files.createTempDirectory("account-api-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    SERVICES.register(r);
    r.add("tasks.role", () -> "all");
    r.add("ai.data-directory", () -> ROOT.resolve("reading").toString());
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springdoc.webmvc.api.OpenApiWebMvcResource openApi;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.data.redis.core.StringRedisTemplate redis;

  @Test
  void redisRateWindowIsSharedAndExpires() throws Exception {
    String prefix = "pdf-workbench:test:rate:" + UUID.randomUUID();
    var first = new cn.edu.pdfworkbench.auth.AccountAttemptLimiter(redis, prefix);
    var second = new cn.edu.pdfworkbench.auth.AccountAttemptLimiter(redis, prefix);
    for (int i = 0; i < 5; i++) first.check("email-ip", 5, true);
    assertEquals(429, assertThrows(cn.edu.pdfworkbench.exception.ApiException.class, () -> second.check("email-ip", 5, false)).status());
    try (var keys = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix + ":attempts:*").count(100).build())) {
      assertTrue(keys.hasNext());
      String key = keys.next();
      assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 900);
      redis.expire(key, java.time.Duration.ofMillis(1));
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
      while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(10);
      assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
      second.check("email-ip", 5, true);
      second.clear("email-ip");
    }
  }

  @Test
  void openApiResourceBuilds() throws Exception {
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setRequestURI("/v3/api-docs");
    assertTrue(openApi.openapiJson(request, "/v3/api-docs", java.util.Locale.ROOT).length > 0);
  }

  @LocalServerPort int port;
  @MockitoBean AiModelClient models;
  final ObjectMapper json = new ObjectMapper();

  class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    String csrf;

    HttpResponse<String> send(String method, String path, Object value) throws Exception {
      return send(method, path, value, null);
    }

    HttpResponse<String> send(String method, String path, Object value, String space) throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
      if (space != null) request.header("X-Chat-Space", space);
      if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
      if (value != null) request.header("Content-Type", "application/json");
      return http.send(
          request
              .method(
                  method,
                  value == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode node(HttpResponse<String> r) throws Exception {
      return json.readTree(r.body());
    }

    void token() throws Exception {
      var r = send("GET", "/api/v1/account/csrf", null);
      assertEquals(200, r.statusCode(), r.body());
      csrf = node(r).get("token").asText();
    }

    void register(String email) throws Exception {
      token();
      var r =
          send(
              "POST",
              "/api/v1/account/register",
              Map.of("email", email, "displayName", "测试账户", "password", "example-password-2026"));
      assertEquals(201, r.statusCode(), r.body());
    }

    void login(String email) throws Exception {
      token();
      var r =
          send(
              "POST",
              "/api/v1/account/login",
              Map.of("email", email, "password", "example-password-2026"));
      assertEquals(200, r.statusCode(), r.body());
      token();
    }
  }

  String email() {
    return UUID.randomUUID() + "@example.com";
  }

  @Test
  void guestAndCsrfBoundaries() throws Exception {
    var c = new Client();
    var r = c.send("GET", "/api/v1/account/session", null);
    assertEquals(200, r.statusCode());
    assertFalse(c.node(r).get("authenticated").asBoolean());
    assertFalse(c.cookies.getCookieStore().getCookies().isEmpty());
    String workspace = c.node(r).get("workspaceId").asText();
    assertEquals(
        workspace,
        c.node(c.send("GET", "/api/v1/account/session", null)).get("workspaceId").asText());
    assertDoesNotThrow(() -> UUID.fromString(workspace));
    assertEquals(403, c.send("POST", "/api/v1/account/register", Map.of()).statusCode());
    assertEquals(401, c.send("GET", "/api/v1/account/profile", null).statusCode());
    assertEquals(
        403,
        c.send("POST", "/api/v1/pdf/ai/conversations/search", Map.of("query", "")).statusCode());
    c.token();
    assertEquals(
        200,
        c.send("POST", "/api/v1/pdf/ai/conversations/search", Map.of("query", "")).statusCode());
    assertEquals(200, c.send("GET", "/v3/api-docs", null).statusCode());
  }

  @Test
  void lifecycleAndOldSessions() throws Exception {
    var a = new Client();
    String email = email();
    a.register(email);
    assertFalse(
        a.node(a.send("GET", "/api/v1/account/session", null)).get("authenticated").asBoolean());
    var before =
        a.cookies.getCookieStore().getCookies().stream().map(HttpCookie::getValue).toList();
    a.login(email);
    assertNotEquals(
        before,
        a.cookies.getCookieStore().getCookies().stream().map(HttpCookie::getValue).toList());
    var b = new Client();
    b.login(email);
    var renamed = a.send("PATCH", "/api/v1/account/profile", Map.of("displayName", "独立昵称"));
    assertEquals(200, renamed.statusCode());
    assertEquals("独立昵称", a.node(renamed).get("displayName").asText());
    assertEquals(
        204,
        a.send(
                "POST",
                "/api/v1/account/password",
                Map.of(
                    "currentPassword",
                    "example-password-2026",
                    "newPassword",
                    "new-example-password-2026"))
            .statusCode());
    assertFalse(
        b.node(b.send("GET", "/api/v1/account/session", null)).get("authenticated").asBoolean());
    a.token();
    assertEquals(
        401,
        a.send(
                "POST",
                "/api/v1/account/login",
                Map.of("email", email, "password", "example-password-2026"))
            .statusCode());
    assertEquals(
        200,
        a.send(
                "POST",
                "/api/v1/account/login",
                Map.of("email", email, "password", "new-example-password-2026"))
            .statusCode());
    a.token();
    assertEquals(204, a.send("POST", "/api/v1/account/logout", null).statusCode());
    assertFalse(
        a.node(a.send("GET", "/api/v1/account/session", null)).get("authenticated").asBoolean());
  }

  @Test
  void validationAndAttemptLimit() throws Exception {
    var c = new Client();
    c.token();
    assertEquals(
        400,
        c.send(
                "POST",
                "/api/v1/account/register",
                Map.of("email", "invalid", "displayName", " ", "password", "short"))
            .statusCode());
    String email = email();
    c.register(email);
    assertEquals(
        409,
        c.send(
                "POST",
                "/api/v1/account/register",
                Map.of(
                    "email",
                    email.toUpperCase(),
                    "displayName",
                    "昵称",
                    "password",
                    "example-password-2026"))
            .statusCode());
    for (int i = 0; i < 5; i++)
      assertEquals(
          401,
          c.send(
                  "POST",
                  "/api/v1/account/login",
                  Map.of("email", email, "password", "wrong-password"))
              .statusCode());
    assertEquals(
        429,
        c.send(
                "POST",
                "/api/v1/account/login",
                Map.of("email", email, "password", "wrong-password"))
            .statusCode());
  }

  byte[] pdf() throws Exception {
    try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
        var out = new java.io.ByteArrayOutputStream()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      try (var text = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        text.beginText();
        text.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        text.newLineAtOffset(40, 700);
        text.showText("Alpha is 42. Independent account evidence.");
        text.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  String upload(Client c) throws Exception {
    if (c.csrf == null) c.token();
    var out = new java.io.ByteArrayOutputStream();
    String boundary = "account-test-boundary";
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"sample.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.write(pdf());
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/pdf/ai/documents"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    if (c.csrf != null) req.header("X-CSRF-TOKEN", c.csrf);
    var response =
        c.http.send(
            req.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return c.node(response).get("id").asText();
  }

  String conversation(Client c, String document) throws Exception {
    var r = c.send("POST", "/api/v1/pdf/ai/conversations", Map.of("documentId", document));
    assertEquals(200, r.statusCode(), r.body());
    return c.node(r).get("id").asText();
  }

  @Test
  void independentGuestsCannotReadModifyOrClaimEachOthersWorkspace() throws Exception {
    var a = new Client();
    var b = new Client();
    String spaceA =
        a.node(a.send("GET", "/api/v1/account/session", null)).get("workspaceId").asText();
    String spaceB =
        b.node(b.send("GET", "/api/v1/account/session", null)).get("workspaceId").asText();
    assertNotEquals(spaceA, spaceB);
    String document = upload(a), chat = conversation(a, document);
    b.token();
    assertEquals(
        0,
        b.node(b.send("POST", "/api/v1/pdf/ai/conversations/search", Map.of("query", ""))).size());
    for (String path :
        List.of(
            "/documents/" + document,
            "/documents/" + document + "/file",
            "/documents/" + document + "/chapters",
            "/conversations/" + chat + "/messages")) {
      assertEquals(404, b.send("GET", "/api/v1/pdf/ai" + path, null).statusCode(), path);
      assertEquals(409, b.send("GET", "/api/v1/pdf/ai" + path, null, spaceA).statusCode(), path);
    }
    assertEquals(
        404,
        b.send("PATCH", "/api/v1/pdf/ai/conversations/" + chat, Map.of("title", "other")).statusCode());
    assertEquals(
        404,
        b.send("DELETE", "/api/v1/pdf/ai/conversations/" + chat, Map.of("deleteDocument", true))
            .statusCode());
    assertEquals(
        200, a.send("GET", "/api/v1/pdf/ai/documents/" + document + "/file", null).statusCode());
    String email = email();
    a.register(email);
    a.login(email);
    assertEquals(404, a.send("GET", "/api/v1/pdf/ai/documents/" + document, null).statusCode());
    assertEquals(204, a.send("POST", "/api/v1/account/logout", null).statusCode());
    String newSpace =
        a.node(a.send("GET", "/api/v1/account/session", null)).get("workspaceId").asText();
    assertNotEquals(spaceA, newSpace);
    assertEquals(404, a.send("GET", "/api/v1/pdf/ai/documents/" + document, null).statusCode());
    assertEquals(409, a.send("GET", "/api/v1/pdf/ai/status", null, spaceA).statusCode());
  }

  @Test
  void accountChatHistoryAndBackgroundRunsAreIsolated() throws Exception {
    when(models.embed(anyList()))
        .thenAnswer(
            call ->
                ((java.util.List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            v -> {
                              float[] vector = new float[1024];
                              vector[0] = 1;
                              return vector;
                            })
                        .toList());
    when(models.complete(anyString(), anyString())).thenReturn("Alpha evidence [p1-0]");
    when(models.rerank(anyString(), anyList()))
        .thenAnswer(
            call ->
                java.util.stream.IntStream.range(
                        0, ((java.util.List<?>) call.getArgument(1)).size())
                    .boxed()
                    .toList());
    doAnswer(
        call -> {
          java.util.function.Consumer<String> delta = call.getArgument(2);
          delta.accept("Alpha is 42 [p1-0]");
          return null;
        })
        .when(models)
        .stream(anyString(), anyString(), any());
    var guest = new Client();
    String guestDoc = upload(guest), guestChat = conversation(guest, guestDoc);
    var a = new Client();
    String emailA = email();
    a.register(emailA);
    a.login(emailA);
    String aDoc = upload(a), aChat = conversation(a, aDoc);
    var b = new Client();
    String emailB = email();
    b.register(emailB);
    b.login(emailB);
    String bDoc = upload(b), bChat = conversation(b, bDoc);
    assertNotEquals(guestDoc, aDoc);
    assertNotEquals(aDoc, bDoc);
    for (var pair :
        List.of(
            new Object[] {a, aChat}, new Object[] {b, bChat}, new Object[] {guest, guestChat})) {
      Client client = (Client) pair[0];
      var list =
          client.node(
              client.send("POST", "/api/v1/pdf/ai/conversations/search", Map.of("query", "")));
      assertEquals(1, list.size());
      assertEquals(pair[1], list.get(0).get("id").asText());
    }
    for (String path :
        List.of(
            "/conversations/" + aChat + "/messages",
            "/documents/" + aDoc,
            "/documents/" + aDoc + "/file",
            "/documents/" + aDoc + "/chapters"))
      assertEquals(404, b.send("GET", "/api/v1/pdf/ai" + path, null).statusCode(), path);
    assertEquals(
        404,
        b.send("PATCH", "/api/v1/pdf/ai/conversations/" + aChat, Map.of("title", "stolen"))
            .statusCode());
    assertEquals(
        404,
        b.send("DELETE", "/api/v1/pdf/ai/conversations/" + aChat, Map.of("deleteDocument", true))
            .statusCode());
    assertEquals(404, guest.send("GET", "/api/v1/pdf/ai/documents/" + aDoc, null).statusCode());
    String run = UUID.randomUUID().toString();
    assertEquals(
        200,
        a.send(
                "POST",
                "/api/v1/pdf/ai/conversations/" + aChat + "/messages",
                Map.of("requestId", run, "mode", "ASK", "question", "What is Alpha?"))
            .statusCode());
    JsonNode result = null;
    for (int i = 0; i < 300; i++) {
      var response = a.send("GET", "/api/v1/pdf/ai/runs/" + run, null);
      assertEquals(200, response.statusCode(), response.body());
      result = a.node(response);
      if (!result.get("status").asText().equals("RUNNING")) break;
      Thread.sleep(20);
    }
    assertEquals("COMPLETED", result.get("status").asText(), result.toString());
    for (String path : List.of("/runs/" + run, "/runs/" + run + "/events"))
      assertEquals(404, b.send("GET", "/api/v1/pdf/ai" + path, null).statusCode());
    assertEquals(404, b.send("POST", "/api/v1/pdf/ai/runs/" + run + "/cancel", null).statusCode());
    assertEquals(
        404,
        b.send(
                "POST",
                "/api/v1/pdf/ai/messages/" + run + "/retry",
                Map.of("requestId", UUID.randomUUID().toString()))
            .statusCode());
    doThrow(new cn.edu.pdfworkbench.exception.ApiException(502, "AI_PROVIDER_ERROR", "模型调用失败"))
        .when(models)
        .stream(anyString(), anyString(), any());
    String failed = UUID.randomUUID().toString();
    assertEquals(
        200,
        a.send(
                "POST",
                "/api/v1/pdf/ai/conversations/" + aChat + "/messages",
                Map.of("requestId", failed, "mode", "ASK", "question", "Second question"))
            .statusCode());
    for (int i = 0; i < 200; i++) {
      result = a.node(a.send("GET", "/api/v1/pdf/ai/runs/" + failed, null));
      if (!result.get("status").asText().equals("RUNNING")) break;
      Thread.sleep(20);
    }
    assertEquals("FAILED", result.get("status").asText());
    assertEquals(404, guest.send("GET", "/api/v1/pdf/ai/runs/" + failed, null).statusCode());
    assertEquals(204, a.send("POST", "/api/v1/account/logout", null).statusCode());
    a.login(emailA);
    assertEquals(
        4,
        a.node(a.send("GET", "/api/v1/pdf/ai/conversations/" + aChat + "/messages", null)).size());
    assertEquals(
        0,
        b.node(b.send("GET", "/api/v1/pdf/ai/conversations/" + bChat + "/messages", null)).size());
  }

  @Test
  @org.junit.jupiter.api.Timeout(40)
  void existingStreamsCloseOnLogoutAndAccountSwitch() throws Exception {
    when(models.embed(anyList()))
        .thenAnswer(
            call ->
                ((java.util.List<?>) call.getArgument(0))
                    .stream()
                        .map(
                            v -> {
                              float[] vector = new float[1024];
                              vector[0] = 1;
                              return vector;
                            })
                        .toList());
    when(models.complete(anyString(), anyString())).thenReturn("Alpha [p1-0]");
    when(models.rerank(anyString(), anyList()))
        .thenAnswer(
            call ->
                java.util.stream.IntStream.range(
                        0, ((java.util.List<?>) call.getArgument(1)).size())
                    .boxed()
                    .toList());
    var a = new Client();
    String emailA = email();
    a.register(emailA);
    a.login(emailA);
    String chat = conversation(a, upload(a));
    var other = new Client();
    String emailB = email();
    other.register(emailB);
    for (String transition : List.of("logout", "account-switch", "guest-login")) {
      boolean fromGuest = transition.equals("guest-login");
      if (fromGuest) {
        assertEquals(204, a.send("POST", "/api/v1/account/logout", null).statusCode());
        a.token();
        chat = conversation(a, upload(a));
      }
      var release = new java.util.concurrent.CountDownLatch(1);
      doAnswer(
          call -> {
            java.util.function.Consumer<String> delta = call.getArgument(2);
            delta.accept("Alpha [p1-0]");
            release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            delta.accept(" PRIVATE_LATE_MARKER");
            return null;
          })
          .when(models)
          .stream(anyString(), anyString(), any());
      String run = UUID.randomUUID().toString();
      assertEquals(
          200,
          a.send(
                  "POST",
                  "/api/v1/pdf/ai/conversations/" + chat + "/messages",
                  Map.of("requestId", run, "mode", "ASK", "question", "Alpha?"))
              .statusCode());
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/pdf/ai/runs/" + run + "/events"))
              .timeout(java.time.Duration.ofSeconds(20))
              .GET()
              .build();
      var response = a.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {}
        if (!transition.equals("logout")) a.login(emailB);
        else assertEquals(204, a.send("POST", "/api/v1/account/logout", null).statusCode());
        release.countDown();
        String tail =
            java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return reader.lines().collect(java.util.stream.Collectors.joining());
                      } catch (Exception e) {
                        throw new RuntimeException(e);
                      }
                    })
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(tail.contains("PRIVATE_LATE_MARKER"));
      } finally {
        release.countDown();
      }
      a.login(emailA);
      if (fromGuest) {
        assertEquals(404, a.send("GET", "/api/v1/pdf/ai/runs/" + run, null).statusCode());
        continue;
      }
      for (int i = 0; i < 200; i++) {
        var result = a.node(a.send("GET", "/api/v1/pdf/ai/runs/" + run, null));
        if (!result.get("status").asText().equals("RUNNING")) break;
        Thread.sleep(20);
      }
    }
  }
}
