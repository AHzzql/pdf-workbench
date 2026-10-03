package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.model.DocumentInfo;
import cn.edu.pdfworkbench.model.Rect;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** 读取书签、Info/XMP 元数据以及注释批注。 */
public final class PdfInfoUtils {
  private static final Set<String> STANDARD_KEYS =
      Set.of(
          "Title",
          "Author",
          "Subject",
          "Keywords",
          "Creator",
          "Producer",
          "CreationDate",
          "ModDate",
          "Trapped");

  private PdfInfoUtils() {}

  /**
   * 提取文档信息；缺失字段不进入映射，异常的可选 XMP 或书签目标进入诊断。
   *
   * @param input PDF 路径
   * @param password 打开密码，允许 null
   * @return 可独立保存的文档信息
   * @throws IOException 文件不可读取、密码或权限错误
   * @throws IllegalArgumentException 输入路径不存在
   */
  public static DocumentInfo read(Path input, String password) throws IOException {
    try (var document = PdfFiles.openForExtraction(input, password)) {
      var standard = new LinkedHashMap<String, String>();
      var custom = new LinkedHashMap<String, String>();
      var information = document.getDocumentInformation();
      for (String key : information.getMetadataKeys()) {
        String value = information.getCustomMetadataValue(key);
        if (value != null) (STANDARD_KEYS.contains(key) ? standard : custom).put(key, value);
      }
      List<String> diagnostics = new ArrayList<>();
      var outline = document.getDocumentCatalog().getDocumentOutline();
      Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      var bookmarks =
          outline == null
              ? List.<DocumentInfo.Bookmark>of()
              : bookmarks(document, outline.getFirstChild(), visited, diagnostics, 0);
      List<DocumentInfo.AnnotationData> annotations = new ArrayList<>();
      int number = 0;
      for (var page : document.getPages()) {
        number++;
        for (var annotation : page.getAnnotations()) {
          var box = annotation.getRectangle();
          Rect bounds =
              box == null
                  ? null
                  : new Rect(
                      box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
          List<Float> quadPoints = new ArrayList<>();
          if (annotation instanceof PDAnnotationTextMarkup markup
              && markup.getQuadPoints() != null) {
            for (float value : markup.getQuadPoints()) quadPoints.add(value);
          }
          String author =
              annotation instanceof PDAnnotationMarkup markup ? markup.getTitlePopup() : null;
          annotations.add(
              new DocumentInfo.AnnotationData(
                  number,
                  annotation.getSubtype(),
                  author,
                  annotation.getContents(),
                  bounds,
                  quadPoints));
        }
      }
      return new DocumentInfo(
          standard, custom, xmp(document, diagnostics), bookmarks, annotations, diagnostics);
    }
  }

  private static List<DocumentInfo.Bookmark> bookmarks(
      PDDocument document,
      PDOutlineItem item,
      Set<COSDictionary> visited,
      List<String> diagnostics,
      int depth)
      throws IOException {
    if (depth > 64) {
      diagnostics.add("书签层级超过 64，后续层级未展开");
      return List.of();
    }
    List<DocumentInfo.Bookmark> result = new ArrayList<>();
    while (item != null) {
      if (!visited.add(item.getCOSObject())) {
        diagnostics.add("书签包含循环引用，已终止重复遍历");
        break;
      }
      Integer page = null;
      try {
        var target = item.findDestinationPage(document);
        int index = target == null ? -1 : document.getPages().indexOf(target);
        if (index >= 0) page = index + 1;
      } catch (IOException | IllegalArgumentException exception) {
        diagnostics.add("存在无法解析的书签目标");
      }
      String uri = item.getAction() instanceof PDActionURI action ? action.getURI() : null;
      result.add(
          new DocumentInfo.Bookmark(
              item.getTitle(),
              page,
              uri,
              bookmarks(document, item.getFirstChild(), visited, diagnostics, depth + 1)));
      item = item.getNextSibling();
    }
    return result;
  }

  private static String xmp(PDDocument document, List<String> diagnostics) throws IOException {
    var metadata = document.getDocumentCatalog().getMetadata();
    if (metadata == null) return null;
    byte[] bytes;
    try (var stream = metadata.exportXMPMetadata()) {
      bytes = stream.readNBytes(1024 * 1024 + 1);
    }
    if (bytes.length > 1024 * 1024) {
      diagnostics.add("XMP 超过 1 MiB，未返回内容");
      return null;
    }
    String xml = new String(bytes, StandardCharsets.UTF_8);
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException {
              throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
              throw exception;
            }
          });
      builder.parse(new InputSource(new StringReader(xml)));
      return xml;
    } catch (SAXException exception) {
      diagnostics.add("XMP 格式错误或含禁止的外部实体声明");
      return null;
    } catch (ParserConfigurationException exception) {
      throw new IOException("无法配置安全的 XML 解析器", exception);
    }
  }
}
