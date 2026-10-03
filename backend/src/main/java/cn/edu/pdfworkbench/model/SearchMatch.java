package cn.edu.pdfworkbench.model;

import java.util.List;

/** 匹配偏移使用页面全文的 UTF-16 索引，end 为开区间。 */
public record SearchMatch(int page, String text, int start, int end, List<Rect> bounds) {
    public SearchMatch { bounds = List.copyOf(bounds); }
}
