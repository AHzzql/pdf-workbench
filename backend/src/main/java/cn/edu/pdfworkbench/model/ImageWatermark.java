package cn.edu.pdfworkbench.model;

import java.nio.file.Path;

/** x、y 为默认用户空间图片左下角；width、height 为等比缩放边界。 */
public record ImageWatermark(
    String pages,
    Path image,
    float x,
    float y,
    float width,
    float height,
    float angle,
    float opacity) {}
