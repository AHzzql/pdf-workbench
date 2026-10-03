package cn.edu.pdfworkbench.model;

import java.util.List;

/** 简单规则表格，空单元格使用空字符串。 */
public record TableData(int page, Rect bounds, List<List<String>> rows) {
    public TableData {
        if (page < 1 || bounds == null || rows == null || rows.isEmpty() || rows.getFirst().isEmpty()) {
            throw new IllegalArgumentException("表格结构为空或页码非法");
        }
        int columns = rows.getFirst().size();
        if (rows.stream().anyMatch(row -> row == null || row.size() != columns)) {
            throw new IllegalArgumentException("表格各行列数必须一致");
        }
        rows = rows.stream().map(List::copyOf).toList();
    }
}
