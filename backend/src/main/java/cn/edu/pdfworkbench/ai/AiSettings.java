package cn.edu.pdfworkbench.ai;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AiSettings(
    @Value("${ai.data-directory:${user.home}/.pdf-workbench}") String dataDirectory,
    @Value("${ai.api-key:}") String apiKey,
    @Value("${ai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String baseUrl,
    @Value(
            "${ai.rerank-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
        String rerankUrl,
    @Value("${ai.chat-model:qwen-plus}") String chatModel,
    @Value("${ai.embedding-model:text-embedding-v3}") String embeddingModel,
    @Value("${ai.rerank-model:gte-rerank-v2}") String rerankModel) {
  public Path root() {
    return Path.of(dataDirectory).toAbsolutePath().normalize();
  }

  public boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String indexVersion() {
    return "pdf-v2-es-cjk-" + embeddingModel + "-1024";
  }

  @Override
  public String toString() {
    return "AiSettings[chatModel=" + chatModel + ", configured=" + configured() + "]";
  }
}
