package cn.edu.pdfworkbench.model;

/** x、y 为默认用户空间基线起点；angle 为逆时针角度，opacity 范围为 0～1。 */
public record TextWatermark(
    String pages,
    String text,
    float x,
    float y,
    float fontSize,
    int rgb,
    float angle,
    float opacity) {}
