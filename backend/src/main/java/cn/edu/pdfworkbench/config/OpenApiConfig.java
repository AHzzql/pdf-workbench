package cn.edu.pdfworkbench.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI api() {
    return new OpenAPI().openapi("3.0.3").info(new Info().title("PDF 文档处理接口").version("1.1.0"));
  }

  @Bean
  OpenApiCustomizer errors() {
    return api ->
        api.getPaths()
            .forEach(
                (url, path) ->
                    path.readOperations()
                        .forEach(
                            operation -> {
                              if (url.startsWith("/api/v1/pdf/ai/")
                                  || url.startsWith("/api/v1/account/")) {
                                var errorSchema =
                                    new ObjectSchema()
                                        .addProperty("status", new IntegerSchema())
                                        .addProperty("code", new StringSchema())
                                        .addProperty("message", new StringSchema());
                                for (String code :
                                    new String[] {
                                      "400", "401", "403", "404", "409", "413", "415", "422", "429",
                                      "500", "502", "503"
                                    })
                                  operation
                                      .getResponses()
                                      .addApiResponse(
                                          code,
                                          new ApiResponse()
                                              .description("请求失败，详细原因见 code 与 message")
                                              .content(
                                                  new Content()
                                                      .addMediaType(
                                                          "application/json",
                                                          new MediaType().schema(errorSchema))));
                                if (url.endsWith("/file"))
                                  operation
                                      .getResponses()
                                      .get("200")
                                      .setContent(
                                          new Content()
                                              .addMediaType(
                                                  "application/pdf",
                                                  new MediaType()
                                                      .schema(
                                                          new StringSchema().format("binary"))));
                                return;
                              }
                              if (!url.startsWith("/api/v1/pdf/")) return;
                              io.swagger.v3.oas.models.media.Schema<?> body =
                                  operation
                                      .getRequestBody()
                                      .getContent()
                                      .get("multipart/form-data")
                                      .getSchema();
                              if (operation.getParameters() != null) {
                                for (var parameter : operation.getParameters()) {
                                  body.addProperty(parameter.getName(), parameter.getSchema());
                                  if (Boolean.TRUE.equals(parameter.getRequired()))
                                    body.addRequiredItem(parameter.getName());
                                }
                                operation.setParameters(null);
                              }
                              if (body.getProperties() != null)
                                body.getProperties().forEach((name, field) -> {
                                  if (name.toLowerCase(java.util.Locale.ROOT).contains("password")) {
                                    field.setWriteOnly(true);
                                    field.setFormat("password");
                                    field.setExample(null);
                                    field.setDefault(null);
                                    field.setDefaultSetFlag(false);
                                    field.setExampleSetFlag(false);
                                  }
                                });
                              Class<?> model =
                                  switch (url) {
                                    case "/api/v1/pdf/text" ->
                                        cn.edu.pdfworkbench.model.TextResult.class;
                                    case "/api/v1/pdf/text/search" ->
                                        cn.edu.pdfworkbench.model.SearchMatch.class;
                                    case "/api/v1/pdf/info" ->
                                        cn.edu.pdfworkbench.model.DocumentInfo.class;
                                    case "/api/v1/pdf/tables" ->
                                        cn.edu.pdfworkbench.model.TableData.class;
                                    case "/api/v1/pdf/signatures/verify" ->
                                        cn.edu.pdfworkbench.model.SignatureResult.class;
                                    default -> null;
                                  };
                              io.swagger.v3.oas.models.media.Schema<?> responseSchema =
                                  new StringSchema().format("binary");
                              String mime =
                                  url.endsWith("images")
                                      ? "application/zip"
                                      : url.endsWith("csv") ? "text/csv" : "application/pdf";
                              if (model != null) {
                                var resolved =
                                    io.swagger.v3.core.converter.ModelConverters.getInstance()
                                        .resolveAsResolvedSchema(
                                            new io.swagger.v3.core.converter.AnnotatedType(model));
                                resolved.referencedSchemas.forEach(
                                    (name, value) -> {
                                      clearResponseDefaults(value);
                                      api.getComponents().addSchemas(name, value);
                                    });
                                responseSchema = resolved.schema;
                                if (model != cn.edu.pdfworkbench.model.TextResult.class
                                    && model != cn.edu.pdfworkbench.model.DocumentInfo.class)
                                  responseSchema =
                                      new io.swagger.v3.oas.models.media.ArraySchema()
                                          .items(responseSchema);
                                mime = "application/json";
                              }
                              operation
                                  .getResponses()
                                  .get("200")
                                  .setContent(
                                      new Content()
                                          .addMediaType(
                                              mime, new MediaType().schema(responseSchema)));
                              if (model == null) {
                                operation.getResponses().get("200").addHeaderObject("Content-Disposition",
                                    new io.swagger.v3.oas.models.headers.Header().schema(new StringSchema()));
                                if (url.endsWith("/images"))
                                  for (String name : new String[] {"X-Images-Total", "X-Images-Exported"})
                                    operation.getResponses().get("200").addHeaderObject(name,
                                        new io.swagger.v3.oas.models.headers.Header().schema(new IntegerSchema()));
                              }
                              var schema =
                                  new ObjectSchema()
                                      .addProperty("status", new IntegerSchema())
                                      .addProperty("code", new StringSchema())
                                      .addProperty("message", new StringSchema());
                              for (String code :
                                  new String[] {"400", "403", "404", "413", "415", "422", "429", "500", "503", "504"})
                                operation
                                    .getResponses()
                                    .addApiResponse(
                                        code,
                                        new ApiResponse()
                                            .description("请求失败")
                                            .content(
                                                new Content()
                                                    .addMediaType(
                                                        "application/json",
                                                        new MediaType().schema(schema))));
                            }));
  }
  private static void clearResponseDefaults(io.swagger.v3.oas.models.media.Schema<?> schema) {
    schema.setDefault(null);
    schema.setDefaultSetFlag(false);
    if (schema.getProperties() != null) schema.getProperties().values().forEach(OpenApiConfig::clearResponseDefaults);
    if (schema.getItems() != null) clearResponseDefaults(schema.getItems());
  }

}
