package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.model.*;
import com.google.re2j.Pattern;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 分页文本、样式及正则检索。 */
public final class PdfTextUtils {
  private PdfTextUtils() {}

  /**
   * 提取指定页的文字及默认用户空间位置，内部文档在返回前关闭。
   *
   * @param input PDF 文件
   * @param password 打开密码，未加密时允许 null
   * @param options 页码与段落选项
   * @return 包含空白页的分页结果
   * @throws IOException 文件读取、密码或权限错误
   * @throws IllegalArgumentException 页码非法
   */
  public static TextResult extract(Path input, String password, TextOptions options)
      throws IOException {
    Objects.requireNonNull(options, "文本选项不能为空");
    try (var document = PdfFiles.openForExtraction(input, password)) {
      var selection = PageSelection.parse(options.pages(), document.getNumberOfPages());
      List<TextResult.PageText> pages = new ArrayList<>();
      for (int number : selection.pages()) {
        var collector = new TextCollector(document.getPage(number - 1));
        collector.setStartPage(number);
        collector.setEndPage(number);
        collector.getText(document);
        pages.add(collector.result(number, options.preserveParagraphs()));
      }
      return new TextResult(pages);
    }
  }

  /**
   * 在保留段落的逐页文本中进行 RE2/J 匹配，不跨页匹配。
   *
   * @param input PDF 文件
   * @param password 打开密码
   * @param options 表达式、页码和匹配标记
   * @return 命中文字、UTF-16 偏移及字形矩形；无命中返回空列表
   * @throws IOException 文件处理失败
   * @throws IllegalArgumentException 表达式或标记不支持
   */
  public static List<SearchMatch> search(Path input, String password, SearchOptions options)
      throws IOException {
    Objects.requireNonNull(options, "搜索选项不能为空");
    if (options.pattern() == null || options.pattern().isBlank()) {
      throw new IllegalArgumentException("搜索表达式不能为空");
    }
    int supported = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL;
    if ((options.flags() & ~supported) != 0) throw new IllegalArgumentException("不支持的搜索标记");
    Pattern pattern;
    try {
      pattern = Pattern.compile(options.pattern(), options.flags());
    } catch (com.google.re2j.PatternSyntaxException exception) {
      throw new IllegalArgumentException("正则表达式语法错误或不受支持", exception);
    }
    var result = extract(input, password, new TextOptions(options.pages(), true));
    List<SearchMatch> matches = new ArrayList<>();
    for (var page : result.pages()) {
      var matcher = pattern.matcher(page.text());
      while (matcher.find()) {
        int start = matcher.start(), end = matcher.end();
        var bounds =
            page.spans().stream()
                .filter(span -> span.start() < end && span.end() > start)
                .map(TextResult.TextSpan::bounds)
                .distinct()
                .toList();
        matches.add(new SearchMatch(page.page(), matcher.group(), start, end, bounds));
      }
    }
    return List.copyOf(matches);
  }
}
