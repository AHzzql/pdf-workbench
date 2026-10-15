package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import cn.edu.pdfworkbench.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** 分块偏移始终指向提取器的原始页文本，检索规范化不改变引用坐标。 */
public final class AiChunker {
  private AiChunker() {}

  private static final Pattern HEADING =
      Pattern.compile("^(第[一二三四五六七八九十百0-9]+[章节篇部].{0,65}|\\d+(?:\\.\\d+){0,3}[、.\\s]+.{1,65})$");

  public static Content split(TextResult result, List<DocumentInfo.Bookmark> bookmarks) {
    var starts = new ArrayList<Chapter>();
    flatten(bookmarks, starts, result.pages().size());
    if (starts.isEmpty())
      for (var page : result.pages()) {
        var matcher = Pattern.compile("(?m)^.{1,80}$").matcher(page.text());
        while (matcher.find())
          if (HEADING.matcher(matcher.group().strip()).matches())
            starts.add(
                new Chapter(
                    "h-" + page.page() + "-" + matcher.start(),
                    matcher.group().strip(),
                    page.page(),
                    matcher.start(),
                    0,
                    0,
                    "识别目录"));
      }
    starts.sort(Comparator.comparingInt(Chapter::page).thenComparingInt(Chapter::offset));
    var chapters = new ArrayList<Chapter>();
    for (int i = 0; i < starts.size(); i++) {
      var c = starts.get(i);
      var next = i + 1 < starts.size() ? starts.get(i + 1) : null;
      chapters.add(
          new Chapter(
              c.id(),
              c.title(),
              c.page(),
              c.offset(),
              next == null ? result.pages().size() : next.page(),
              next == null ? Integer.MAX_VALUE : next.offset(),
              c.source()));
    }
    var chunks = new ArrayList<Chunk>();
    for (var page : result.pages()) {
      var boundaries = new TreeSet<Integer>();
      boundaries.add(0);
      boundaries.add(page.text().length());
      chapters.stream()
          .filter(c -> c.page() == page.page())
          .forEach(c -> boundaries.add(c.offset()));
      var ends = new ArrayList<>(boundaries);
      for (int b = 0; b + 1 < ends.size(); b++) {
        int start = ends.get(b), limit = ends.get(b + 1);
        while (start < limit) {
          int end = Math.min(start + 1200, limit);
          if (end < limit) {
            int newline = page.text().lastIndexOf('\n', end);
            if (newline > start + 600) end = newline + 1;
          }
          if (end < limit && Character.isHighSurrogate(page.text().charAt(end - 1))) end--;
          String text = page.text().substring(start, end);
          final int from = start, to = end;
          var bounds =
              mergeBounds(
                  page.spans().stream()
                      .filter(s -> s.start() < to && s.end() > from)
                      .map(TextResult.TextSpan::bounds)
                      .toList());
          if (!text.isBlank())
            chunks.add(
                new Chunk("p" + page.page() + "-" + start, page.page(), start, end, text, bounds));
          if (end == limit) break;
          start = Math.max(start + 1, end - 120);
          if (Character.isLowSurrogate(page.text().charAt(start))) start++;
        }
      }
    }
    return new Content(List.copyOf(chunks), List.copyOf(chapters));
  }

  private static List<Rect> mergeBounds(List<Rect> input) {
    var out = new ArrayList<Rect>();
    for (var r : input) {
      if (!out.isEmpty()) {
        var p = out.getLast();
        if (Math.abs(p.y() - r.y()) < 2
            && Math.abs(p.height() - r.height()) < 3
            && r.x() >= p.x()
            && r.x() <= p.x() + p.width() + 8) {
          out.set(
              out.size() - 1,
              new Rect(
                  p.x(),
                  Math.min(p.y(), r.y()),
                  Math.max(p.width(), r.x() + r.width() - p.x()),
                  Math.max(p.height(), r.height())));
          continue;
        }
      }
      out.add(r);
    }
    return List.copyOf(out);
  }

  private static void flatten(List<DocumentInfo.Bookmark> bookmarks, List<Chapter> out, int pages) {
    for (var b : bookmarks) {
      if (b.page() != null
          && b.page() > 0
          && b.page() <= pages
          && out.stream().noneMatch(c -> c.page() == b.page()))
        out.add(new Chapter("b-" + out.size(), b.title(), b.page(), 0, 0, 0, "PDF 书签"));
      flatten(b.children(), out, pages);
    }
  }
}
