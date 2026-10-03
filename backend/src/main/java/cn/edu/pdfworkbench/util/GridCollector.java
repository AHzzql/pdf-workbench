package cn.edu.pdfworkbench.util;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/** 只收集实际描边或填充路径中的轴向线段；忽略图片与着色。 */
final class GridCollector extends PDFGraphicsStreamEngine {
    static final float TOLERANCE = 1;
    private final List<Segment> pending = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    private Point2D current = new Point2D.Float();
    private Point2D start = current;

    GridCollector(PDPage page) { super(page); }

    List<Segment> segments() { return List.copyOf(segments); }

    private void segment(Point2D a, Point2D b) {
        if (Math.abs(a.getY() - b.getY()) <= TOLERANCE && Math.abs(a.getX() - b.getX()) > TOLERANCE) {
            pending.add(new Segment(true, (float) ((a.getY() + b.getY()) / 2),
                    (float) Math.min(a.getX(), b.getX()), (float) Math.max(a.getX(), b.getX())));
        } else if (Math.abs(a.getX() - b.getX()) <= TOLERANCE && Math.abs(a.getY() - b.getY()) > TOLERANCE) {
            pending.add(new Segment(false, (float) ((a.getX() + b.getX()) / 2),
                    (float) Math.min(a.getY(), b.getY()), (float) Math.max(a.getY(), b.getY())));
        }
    }

    @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
        segment(a,b); segment(b,c); segment(c,d); segment(d,a); current = a; start = a;
    }
    @Override public void moveTo(float x, float y) { current = new Point2D.Float(x,y); start = current; }
    @Override public void lineTo(float x, float y) {
        Point2D next = new Point2D.Float(x,y); segment(current,next); current = next;
    }
    @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3) {
        current = new Point2D.Float(x3,y3);
    }
    @Override public Point2D getCurrentPoint() { return current; }
    @Override public void closePath() { segment(current,start); current = start; }
    @Override public void endPath() { pending.clear(); }
    @Override public void strokePath() { segments.addAll(pending); pending.clear(); }
    @Override public void fillPath(int windingRule) { strokePath(); }
    @Override public void fillAndStrokePath(int windingRule) { strokePath(); }
    @Override public void clip(int windingRule) { }
    @Override public void drawImage(PDImage image) { }
    @Override public void shadingFill(org.apache.pdfbox.cos.COSName name) { }

    record Segment(boolean horizontal, float axis, float start, float end) {
        boolean touches(Segment other) {
            if (horizontal == other.horizontal) {
                return Math.abs(axis - other.axis) <= TOLERANCE
                        && start <= other.end + TOLERANCE && other.start <= end + TOLERANCE;
            }
            return axis >= other.start - TOLERANCE && axis <= other.end + TOLERANCE
                    && other.axis >= start - TOLERANCE && other.axis <= end + TOLERANCE;
        }
    }
}
