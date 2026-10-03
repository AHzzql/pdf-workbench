package cn.edu.pdfworkbench.util;

import cn.edu.pdfworkbench.exception.PdfProcessingException;
import cn.edu.pdfworkbench.model.ImageData;
import cn.edu.pdfworkbench.model.Rect;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;

/** 页面绘制遍历保留位置，资源遍历补充未使用图片及蒙版。 */
final class ImageCollector extends PDFGraphicsStreamEngine {
  static final long MAX_PIXELS = 40_000_000;
  static final int MAX_RAW_BYTES = 200 * 1024 * 1024;
  private final List<Entry> entries;
  private final java.util.Map<COSDictionary, Entry> byObject;
  private final Set<COSDictionary> visitedResources =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<COSDictionary> visitedStreams =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final int pageNumber;
  private boolean recordPositions = true;

  private ImageCollector(
      PDPage page, int number, List<Entry> entries, java.util.Map<COSDictionary, Entry> byObject) {
    super(page);
    this.pageNumber = number;
    this.entries = entries;
    this.byObject = byObject;
  }

  static List<Entry> collect(PDDocument document) throws IOException {
    List<Entry> entries = new ArrayList<>();
    var byObject = new IdentityHashMap<COSDictionary, Entry>();
    int number = 0;
    for (var page : document.getPages()) {
      var collector = new ImageCollector(page, ++number, entries, byObject);
      collector.processPage(page);
      collector.scan(page.getResources(), 0);
      for (var annotation : page.getAnnotations()) {
        var appearance = annotation.getAppearance();
        if (appearance != null) {
          collector.appearance(appearance.getNormalAppearance());
          collector.appearance(appearance.getRolloverAppearance());
          collector.appearance(appearance.getDownAppearance());
        }
      }
      var thumbnail = page.getCOSObject().getDictionaryObject(COSName.THUMB);
      if (thumbnail instanceof COSStream stream)
        collector.register(PDImageXObject.createThumbnail(stream), "thumbnail");
    }
    return entries;
  }

  private void appearance(PDAppearanceEntry appearance) throws IOException {
    if (appearance == null) return;
    var streams =
        appearance.isStream()
            ? List.of(appearance.getAppearanceStream())
            : new ArrayList<>(appearance.getSubDictionary().values());
    for (var stream : streams) {
      processChildStream(stream, getPage());
      scan(stream.getResources(), 0);
    }
  }

  private void scan(PDResources resources, int depth) throws IOException {
    if (resources == null || !visitedResources.add(resources.getCOSObject())) return;
    if (depth > 64) throw new PdfProcessingException("INPUT_LIMIT_EXCEEDED", "图片资源层级超过限制");
    for (COSName name : resources.getXObjectNames()) {
      var object = resources.getXObject(name);
      if (object instanceof PDImageXObject image) register(image, "resource");
      else if (object instanceof PDFormXObject form) {
        if (visitedStreams.add(form.getCOSObject())) inspectStream(form);
        scan(form.getResources(), depth + 1);
      }
    }
    for (COSName name : resources.getPatternNames()) {
      if (resources.getPattern(name)
          instanceof org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern pattern) {
        if (visitedStreams.add(pattern.getCOSObject())) inspectStream(pattern);
        scan(pattern.getResources(), depth + 1);
      }
    }
  }

  private void inspectStream(org.apache.pdfbox.contentstream.PDContentStream stream)
      throws IOException {
    boolean previous = recordPositions;
    recordPositions = false;
    try {
      processChildStream(stream, getPage());
    } finally {
      recordPositions = previous;
    }
  }

  @Override
  public void showForm(PDFormXObject form) throws IOException {
    visitedStreams.add(form.getCOSObject());
    super.showForm(form);
  }

  private Entry register(PDImage image, String source) throws IOException {
    Entry existing = byObject.get(image.getCOSObject());
    if (existing != null) return existing;
    if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
      throw new PdfProcessingException("INPUT_LIMIT_EXCEEDED", "图片或蒙版像素数超过限制");
    }
    var entry = new Entry("image-" + (entries.size() + 1), source, image);
    entries.add(entry);
    byObject.put(image.getCOSObject(), entry);
    if (entries.size() > 10_000)
      throw new PdfProcessingException("INPUT_LIMIT_EXCEEDED", "图片数量超过限制");
    if (image instanceof PDImageXObject xobject) {
      var mask = xobject.getMask();
      if (mask != null) register(mask, "mask");
      var softMask = xobject.getSoftMask();
      if (softMask != null) register(softMask, "mask");
    }
    return entry;
  }

  @Override
  public void drawImage(PDImage image) throws IOException {
    var entry = register(image, image instanceof PDInlineImage ? "inline" : "resource");
    if (!recordPositions) return;
    var matrix = getGraphicsState().getCurrentTransformationMatrix();
    Point2D.Float[] points = {
      matrix.transformPoint(0, 0),
      matrix.transformPoint(1, 0),
      matrix.transformPoint(0, 1),
      matrix.transformPoint(1, 1)
    };
    float x = Float.POSITIVE_INFINITY,
        y = Float.POSITIVE_INFINITY,
        right = Float.NEGATIVE_INFINITY,
        top = Float.NEGATIVE_INFINITY;
    for (var point : points) {
      x = Math.min(x, point.x);
      y = Math.min(y, point.y);
      right = Math.max(right, point.x);
      top = Math.max(top, point.y);
    }
    entry.occurrences.add(new ImageData.Occurrence(pageNumber, new Rect(x, y, right - x, top - y)));
  }

  // 路径绘制不影响原始图片资源与图像变换矩阵的提取。
  @Override
  public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {}

  @Override
  public void clip(int windingRule) {}

  @Override
  public void moveTo(float x, float y) {}

  @Override
  public void lineTo(float x, float y) {}

  @Override
  public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}

  @Override
  public Point2D getCurrentPoint() {
    return new Point2D.Float();
  }

  @Override
  public void closePath() {}

  @Override
  public void endPath() {}

  @Override
  public void strokePath() {}

  @Override
  public void fillPath(int windingRule) {}

  @Override
  public void fillAndStrokePath(int windingRule) {}

  @Override
  public void shadingFill(COSName name) {}

  static final class Entry {
    final String id;
    final String source;
    final PDImage image;
    final List<ImageData.Occurrence> occurrences = new ArrayList<>();

    Entry(String id, String source, PDImage image) {
      this.id = id;
      this.source = source;
      this.image = image;
    }
  }
}
