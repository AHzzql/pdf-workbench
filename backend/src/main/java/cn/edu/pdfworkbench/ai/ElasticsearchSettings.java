package cn.edu.pdfworkbench.ai;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ElasticsearchSettings(
    @Value("${search.url:http://127.0.0.1:19200}") String url,
    @Value("${search.username:elastic}") String username,
    @Value("${search.password:}") String password,
    @Value("${search.alias:pdf-workbench-chunks}") String alias) {
  public ElasticsearchSettings {
    var uri = URI.create(url);
    if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || !java.util.Set.of("", "/").contains(uri.getPath()))
      throw new IllegalArgumentException("Invalid Elasticsearch endpoint");
    if (alias == null || !alias.matches("pdf-workbench-[a-z0-9-]{1,160}"))
      throw new IllegalArgumentException("Invalid Elasticsearch alias");
    url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public String physicalIndex() { return alias + "-v1"; }

  @Override
  public String toString() { return "ElasticsearchSettings[alias=" + alias + "]"; }
}
