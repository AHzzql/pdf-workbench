package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.model.*;
import cn.edu.pdfworkbench.model.TextResult.TextSpan;
import cn.edu.pdfworkbench.util.GridCollector.Segment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 水平、垂直边框构成的简单规则表格。 */
public final class PdfTableUtils {
  private PdfTableUtils() {}

  /**
   * 提取指定页中完整的规则网格，空单元格保留，复杂合并结构不作为规则表返回。
   *
   * @param input PDF 文件
   * @param password 打开密码
   * @param options 页码与可选区域
   * @return 按页和页面位置排列的表格；无表格返回空列表
   * @throws IOException 文件读取、密码或权限错误
   * @throws IllegalArgumentException 页码或区域非法
   */
  public static List<TableData> extract(Path input, String password, TableOptions options)
      throws IOException {
    if (options == null) throw new IllegalArgumentException("表格选项不能为空");
    Rect region = options.region();
    if (region != null
        && (!Float.isFinite(region.x())
            || !Float.isFinite(region.y())
            || !Float.isFinite(region.width())
            || !Float.isFinite(region.height())
            || region.width() <= 0
            || region.height() <= 0)) {
      throw new IllegalArgumentException("表格区域非法");
    }
    List<TableData> tables = new ArrayList<>();
    try (var document = PdfFiles.openForExtraction(input, password)) {
      for (int page : PageSelection.parse(options.pages(), document.getNumberOfPages()).pages()) {
        var source = document.getPage(page - 1);
        var collector = new GridCollector(source);
        collector.processPage(source);
        var textCollector = new TextCollector(source);
        textCollector.setStartPage(page);
        textCollector.setEndPage(page);
        textCollector.getText(document);
        var spans = textCollector.result(page, false).spans();
        for (var group : components(collector.segments())) {
          var xs = axes(group, false);
          var ys = axes(group, true);
          if (xs.size() < 2 || ys.size() < 2 || !isGrid(group, xs, ys)) continue;
          var bounds =
              new Rect(
                  xs.getFirst(),
                  ys.getFirst(),
                  xs.getLast() - xs.getFirst(),
                  ys.getLast() - ys.getFirst());
          if (region != null && !contains(region, bounds)) continue;
          List<List<String>> rows = new ArrayList<>();
          for (int row = ys.size() - 2; row >= 0; row--) {
            List<String> cells = new ArrayList<>();
            for (int col = 0; col < xs.size() - 1; col++) {
              var cell =
                  new Rect(
                      xs.get(col),
                      ys.get(row),
                      xs.get(col + 1) - xs.get(col),
                      ys.get(row + 1) - ys.get(row));
              cells.add(cellText(spans, cell));
            }
            rows.add(cells);
          }
          tables.add(new TableData(page, bounds, rows));
        }
      }
    }
    tables.sort(
        Comparator.comparingInt(TableData::page)
            .thenComparingDouble(t -> -t.bounds().y())
            .thenComparingDouble(t -> t.bounds().x()));
    return List.copyOf(tables);
  }

  /**
   * 以 UTF-8 BOM 写出 CSV，字段全部加引号，公式前缀添加单引号用于电子表格安全打开。
   *
   * @param table 表格数据
   * @param output 输出文件路径，父目录必须存在
   * @throws IOException 无法写入输出文件
   * @throws IllegalArgumentException 参数为空
   */
  public static void writeCsv(TableData table, Path output) throws IOException {
    if (table == null || output == null) throw new IllegalArgumentException("表格与输出路径不能为空");
    Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), "csv-", ".tmp");
    try {
      writeCsvContent(table, temporary);
      Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void writeCsvContent(TableData table, Path output) throws IOException {
    try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      writer.write('\uFEFF');
      for (List<String> row : table.rows()) {
        for (int index = 0; index < row.size(); index++) {
          if (index > 0) writer.write(',');
          String value = row.get(index);
          String stripped = value.stripLeading();
          if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) value = "'" + value;
          writer.write('"');
          writer.write(value.replace("\"", "\"\""));
          writer.write('"');
        }
        writer.write("\r\n");
      }
    }
  }

  private static boolean contains(Rect outer, Rect inner) {
    return inner.x() >= outer.x()
        && inner.y() >= outer.y()
        && inner.x() + inner.width() <= outer.x() + outer.width()
        && inner.y() + inner.height() <= outer.y() + outer.height();
  }

  private static String cellText(List<TextSpan> spans, Rect cell) {
    var selected =
        spans.stream()
            .filter(
                s -> {
                  float x = s.bounds().x() + s.bounds().width() / 2,
                      y = s.bounds().y() + s.bounds().height() / 2;
                  return x >= cell.x()
                      && x < cell.x() + cell.width()
                      && y >= cell.y()
                      && y < cell.y() + cell.height();
                })
            .sorted(
                Comparator.comparingDouble((TextSpan s) -> -s.baselineY())
                    .thenComparingDouble(s -> s.bounds().x()))
            .toList();
    StringBuilder text = new StringBuilder();
    TextSpan previous = null;
    for (TextSpan span : selected) {
      if (previous != null) {
        if (Math.abs(previous.baselineY() - span.baselineY()) > span.fontSize() * 0.25)
          text.append('\n');
        else if (span.start() > previous.end()) text.append(' ');
      }
      text.append(span.text());
      previous = span;
    }
    return text.toString().strip();
  }

  private static List<List<Segment>> components(List<Segment> input) {
    var remaining = new ArrayList<>(input);
    List<List<Segment>> groups = new ArrayList<>();
    while (!remaining.isEmpty()) {
      var queue = new ArrayDeque<Segment>();
      queue.add(remaining.removeLast());
      List<Segment> group = new ArrayList<>();
      while (!queue.isEmpty()) {
        Segment current = queue.remove();
        group.add(current);
        var iterator = remaining.iterator();
        while (iterator.hasNext()) {
          Segment candidate = iterator.next();
          if (current.touches(candidate)) {
            queue.add(candidate);
            iterator.remove();
          }
        }
      }
      groups.add(group);
    }
    return groups;
  }

  private static List<Float> axes(List<Segment> group, boolean horizontal) {
    var sorted =
        group.stream()
            .filter(s -> s.horizontal() == horizontal)
            .map(Segment::axis)
            .sorted()
            .toList();
    List<Float> unique = new ArrayList<>();
    for (float value : sorted) {
      if (unique.isEmpty() || value - unique.getLast() > GridCollector.TOLERANCE) unique.add(value);
    }
    return unique;
  }

  private static boolean isGrid(List<Segment> group, List<Float> xs, List<Float> ys) {
    for (float x : xs) if (!covers(group, false, x, ys.getFirst(), ys.getLast())) return false;
    for (float y : ys) if (!covers(group, true, y, xs.getFirst(), xs.getLast())) return false;
    return true;
  }

  private static boolean covers(
      List<Segment> group, boolean horizontal, float axis, float start, float end) {
    float cursor = start;
    var segments =
        group.stream()
            .filter(
                s ->
                    s.horizontal() == horizontal
                        && Math.abs(s.axis() - axis) <= GridCollector.TOLERANCE)
            .sorted(Comparator.comparingDouble(Segment::start))
            .toList();
    for (Segment segment : segments) {
      if (segment.start() > cursor + GridCollector.TOLERANCE) return false;
      cursor = Math.max(cursor, segment.end());
      if (cursor >= end - GridCollector.TOLERANCE) return true;
    }
    return false;
  }
}
