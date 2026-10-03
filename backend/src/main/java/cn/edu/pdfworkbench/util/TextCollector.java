package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.model.Rect;
import cn.edu.pdfworkbench.model.TextResult;
import cn.edu.pdfworkbench.model.TextResult.TextColor;
import org.apache.pdfbox.contentstream.operator.color.*;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TextCollector extends PDFTextStripper {
    private final List<Glyph> glyphs = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private PDPage page;

    TextCollector(PDPage current) throws IOException {
        page = current;
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetStrokingColorSpace(this));
        addOperator(new SetStrokingColor(this));
        addOperator(new SetStrokingColorN(this));
        addOperator(new SetStrokingDeviceRGBColor(this));
        addOperator(new SetStrokingDeviceCMYKColor(this));
        addOperator(new SetStrokingDeviceGrayColor(this));
    }

    @Override
    protected void startPage(PDPage current) {
        page = current;
        glyphs.clear();
        diagnostics.clear();
    }

    @Override
    protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
        if (font.toUnicode(code) == null) {
            String message = "存在缺少 Unicode 映射的字形，未转换为文字";
            if (!diagnostics.contains(message)) diagnostics.add(message);
            return;
        }
        super.showGlyph(matrix, font, code, displacement);
    }

    @Override
    protected void processTextPosition(TextPosition position) {
        var crop = page.getCropBox();
        var matrix = position.getTextMatrix();
        float scaleX = Math.max(0.0001f, matrix.getScalingFactorX());
        float scaleY = Math.max(0.0001f, matrix.getScalingFactorY());
        float width = position.getWidthDirAdj() / scaleX;
        float height = position.getHeightDir() / scaleY;
        // TextPosition 的矩阵已经减去 CropBox 原点，恢复默认用户空间后再返回。
        Point2D.Float[] corners = {
                matrix.transformPoint(0, 0), matrix.transformPoint(width, 0),
                matrix.transformPoint(0, height), matrix.transformPoint(width, height)
        };
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (var point : corners) {
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
        }
        var rectangle = new Rect(minX + crop.getLowerLeftX(), minY + crop.getLowerLeftY(),
                maxX - minX, maxY - minY);
        var state = getGraphicsState();
        glyphs.add(new Glyph(position.getUnicode(), rectangle,
                matrix.getTranslateY() + crop.getLowerLeftY(), position.getFont().getName(), scaleY,
                color(state.getNonStrokingColor()), color(state.getStrokingColor()),
                state.getTextState().getRenderingMode().intValue(), position.getWidthOfSpace()));
        if (position.getDir() != 0 && !diagnostics.contains("非横排文字的阅读顺序仅按位置排列")) {
            diagnostics.add("非横排文字的阅读顺序仅按位置排列");
        }
    }

    private TextColor color(PDColor value) {
        List<Float> components = new ArrayList<>();
        for (float number : value.getComponents()) components.add(number);
        String rgb = null;
        try {
            rgb = String.format("#%06X", value.toRGB() & 0xFFFFFF);
        } catch (IOException | UnsupportedOperationException exception) {
            String message = "颜色空间无法转换为 RGB：" + value.getColorSpace().getName();
            if (!diagnostics.contains(message)) diagnostics.add(message);
        }
        return new TextColor(value.getColorSpace().getName(), components, rgb);
    }

    TextResult.PageText result(int pageNumber, boolean preserveParagraphs) {
        List<List<Glyph>> columns = splitColumns(glyphs);
        StringBuilder text = new StringBuilder();
        List<TextResult.TextSpan> spans = new ArrayList<>();
        List<TextResult.Paragraph> paragraphs = new ArrayList<>();
        int paragraphStart = 0;
        for (List<Glyph> column : columns) {
            List<List<Glyph>> lines = lines(column);
            Glyph previousLine = null;
            for (List<Glyph> line : lines) {
                Glyph first = line.getFirst();
                if (!text.isEmpty()) {
                    boolean newParagraph = previousLine == null
                            || previousLine.baseline - first.baseline > Math.max(first.size, previousLine.size) * 1.6;
                    if (newParagraph && preserveParagraphs) {
                        paragraphs.add(new TextResult.Paragraph(text.substring(paragraphStart), paragraphStart, text.length()));
                        text.append("\n\n");
                        paragraphStart = text.length();
                    } else {
                        text.append('\n');
                    }
                }
                Glyph previous = null;
                for (Glyph glyph : line) {
                    if (previous != null && !glyph.text.isBlank() && !previous.text.isBlank()
                            && glyph.rect.x() - (previous.rect.x() + previous.rect.width())
                            > Math.max(1, previous.space * 0.5f)) {
                        text.append(' ');
                    }
                    int start = text.length();
                    text.append(glyph.text);
                    spans.add(new TextResult.TextSpan(glyph.text, start, text.length(), glyph.rect,
                            glyph.baseline, glyph.font, glyph.size, glyph.fill, glyph.stroke, glyph.mode));
                    previous = glyph;
                }
                previousLine = first;
            }
        }
        if (preserveParagraphs && !text.isEmpty()) {
            paragraphs.add(new TextResult.Paragraph(text.substring(paragraphStart), paragraphStart, text.length()));
        }
        var box = page.getCropBox();
        return new TextResult.PageText(pageNumber,
                new Rect(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight()),
                page.getRotation(), page.getUserUnit(), text.toString(), spans, paragraphs, diagnostics);
    }

    private static List<List<Glyph>> splitColumns(List<Glyph> input) {
        if (input.isEmpty()) return List.of();
        var sorted = input.stream().sorted(Comparator.comparingDouble(g -> g.rect.x())).toList();
        float right = sorted.getFirst().rect.x() + sorted.getFirst().rect.width();
        float largestGap = 36, split = Float.NaN;
        for (Glyph glyph : sorted) {
            float gap = glyph.rect.x() - right;
            if (gap > largestGap) {
                largestGap = gap;
                split = right + gap / 2;
            }
            right = Math.max(right, glyph.rect.x() + glyph.rect.width());
        }
        if (Float.isNaN(split)) return List.of(input);
        float boundary = split;
        var left = input.stream().filter(g -> g.rect.x() < boundary).toList();
        var second = input.stream().filter(g -> g.rect.x() >= boundary).toList();
        // 至少两行重复对齐才判为双栏，避免把一行内的大间距视作栏间距。
        if (lines(left).size() < 2 || lines(second).size() < 2) return List.of(input);
        return List.of(left, second);
    }

    private static List<List<Glyph>> lines(List<Glyph> input) {
        List<List<Glyph>> result = new ArrayList<>();
        var sorted = input.stream().sorted(Comparator.comparingDouble((Glyph g) -> -g.baseline)
                .thenComparingDouble(g -> g.rect.x())).toList();
        for (Glyph glyph : sorted) {
            if (result.isEmpty() || Math.abs(result.getLast().getFirst().baseline - glyph.baseline)
                    > Math.max(1, glyph.size * 0.25)) {
                result.add(new ArrayList<>());
            }
            result.getLast().add(glyph);
        }
        result.forEach(line -> line.sort(Comparator.comparingDouble(g -> g.rect.x())));
        return result;
    }

    private record Glyph(String text, Rect rect, float baseline, String font, float size,
                         TextColor fill, TextColor stroke, int mode, float space) { }
}
