package cn.edu.pdfworkbench.model;

/** 可选表格区域使用默认用户空间坐标；只选择完整包含的表格。 */
public record TableOptions(String pages, Rect region) { }
