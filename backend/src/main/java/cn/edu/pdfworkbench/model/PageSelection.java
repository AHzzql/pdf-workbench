package cn.edu.pdfworkbench.model;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** 页码范围，使用从 1 开始的文档页码。 */
public record PageSelection(List<Integer> pages) {
    private static final Pattern RANGE = Pattern.compile("([0-9]+)(?:\\s*-\\s*([0-9]+))?");

    public PageSelection {
        pages = List.copyOf(pages);
        if (pages.stream().anyMatch(page -> page < 1)) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
    }

    /**
     * 解析逗号分隔的单页与闭区间，去重并升序排列；空参数选择全部页。
     * @param expression 页码表达式，如 1,3-5
     * @param pageCount 文档页数，允许空文档
     * @return 不可修改的页码集合
     * @throws IllegalArgumentException 表达式非法或页码越界
     */
    public static PageSelection parse(String expression, int pageCount) {
        if (pageCount < 0) {
            throw new IllegalArgumentException("文档页数不能为负数");
        }
        if (expression == null || expression.isBlank()) {
            return new PageSelection(IntStream.rangeClosed(1, pageCount).boxed().toList());
        }
        var selected = new TreeSet<Integer>();
        for (String part : expression.split(",", -1)) {
            var matcher = RANGE.matcher(part.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("页码格式错误");
            }
            int start;
            int end;
            try {
                start = Integer.parseInt(matcher.group(1));
                end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("页码超出整数范围", exception);
            }
            if (start < 1 || end < start || end > pageCount) {
                throw new IllegalArgumentException("页码超出文档范围");
            }
            IntStream.rangeClosed(start, end).forEach(selected::add);
        }
        return new PageSelection(List.copyOf(selected));
    }
}
