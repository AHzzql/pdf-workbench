package cn.edu.pdfworkbench.model;

/** flags 使用 RE2/J 的 CASE_INSENSITIVE、MULTILINE 或 DOTALL 位。 */
public record SearchOptions(String pages, String pattern, int flags) { }
