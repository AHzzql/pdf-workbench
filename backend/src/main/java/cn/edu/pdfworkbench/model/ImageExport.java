package cn.edu.pdfworkbench.model;

import java.nio.file.Path;

/** file 为空表示该图片导出失败，原因保存在 diagnostic。 */
public record ImageExport(String id, Path file, String diagnostic) {}
