package cn.edu.pdfworkbench.ai;

import cn.edu.pdfworkbench.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 官方模型适配器处理对话与向量协议，排序接口单独调用百炼服务。 */
@Component
public class AiModelClient {
  private final AiSettings settings;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private volatile OpenAiApi api;

  public AiModelClient(AiSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  public void requireConfigured() {
    if (!settings.configured()) throw new ApiException(503, "AI_NOT_CONFIGURED", "尚未配置 AI 模型密钥");
  }

  private synchronized OpenAiApi api() {
    requireConfigured();
    if (api == null) {
      var factory = new JdkClientHttpRequestFactory(http);
      factory.setReadTimeout(Duration.ofSeconds(90));
      api =
          OpenAiApi.builder()
              .baseUrl(settings.baseUrl())
              .apiKey(settings.apiKey())
              .restClientBuilder(RestClient.builder().requestFactory(factory))
              .build();
    }
    return api;
  }

  public List<float[]> embed(List<String> texts) {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
    try {
      return new OpenAiEmbeddingModel(
              api(),
              MetadataMode.NONE,
              OpenAiEmbeddingOptions.builder()
                  .model(settings.embeddingModel())
                  .dimensions(1024)
                  .build(),
              RetryTemplate.builder().maxAttempts(1).build())
          .embed(texts);
    } catch (RuntimeException e) {
      throw modelError(e);
    }
  }

  public void stream(String system, String user, Consumer<String> consumer) {
    try {
      var model =
          OpenAiChatModel.builder()
              .openAiApi(api())
              .defaultOptions(
                  OpenAiChatOptions.builder()
                      .model(settings.chatModel())
                      .temperature(0.2)
                      .maxTokens(4000)
                      .build())
              .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
              .build();
      model.stream(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
          .timeout(Duration.ofSeconds(90))
          .doOnNext(
              response -> {
                if (response.getResult() != null
                    && response.getResult().getOutput().getText() != null)
                  consumer.accept(response.getResult().getOutput().getText());
              })
          .blockLast(Duration.ofMinutes(8));
    } catch (RuntimeException e) {
      throw modelError(e);
    }
  }

  public String complete(String system, String user) {
    var out = new StringBuilder();
    stream(
        system,
        user,
        s -> {
          if (out.length() + s.length() > 20000)
            throw new ApiException(413, "AI_OUTPUT_LIMIT", "模型输出超过限制");
          out.append(s);
        });
    return out.toString();
  }

  public List<Integer> rerank(String query, List<String> documents) {
    requireConfigured();
    if (documents.isEmpty()) return List.of();
    try {
      var body =
          Map.of(
              "model",
              settings.rerankModel(),
              "input",
              Map.of("query", query, "documents", documents),
              "parameters",
              Map.of("top_n", Math.min(8, documents.size()), "return_documents", false));
      var request =
          HttpRequest.newBuilder(URI.create(settings.rerankUrl()))
              .timeout(Duration.ofSeconds(90))
              .header("Authorization", "Bearer " + settings.apiKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ApiException(502, "AI_PROVIDER_ERROR", "重排序服务请求失败，请检查模型权限或稍后重试");
      var results = json.readTree(response.body()).path("output").path("results");
      if (!results.isArray()) throw new IllegalStateException("Invalid rerank response");
      var indexes = new ArrayList<Integer>();
      for (var result : results) {
        int i = result.path("index").asInt(-1);
        if (i < 0 || i >= documents.size() || indexes.contains(i))
          throw new IllegalStateException("Invalid rerank index");
        indexes.add(i);
      }
      return indexes;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    } catch (Exception e) {
      throw modelError(e);
    }
  }

  private RuntimeException modelError(Exception e) {
    if (e instanceof ApiException a) return a;
    if (Thread.currentThread().isInterrupted())
      return new java.util.concurrent.CancellationException();
    return new ApiException(502, "AI_PROVIDER_ERROR", "模型调用失败，请检查网络、密钥、额度和模型权限后重试");
  }
}
