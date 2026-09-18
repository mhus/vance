package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Geometric outlines → scribble stroke points (sheet units, ints, fixed
 * pressure 0.5). The shapes an agent can legitimately draw — a house outline
 * is a {@code triangle} roof on a {@code rectangle}, nothing hand-made.
 * Shapes never pretend to be handwriting; they are diagram ink.
 */
public final class ScribbleShapeGeometry {

    /** Sampling density for curved outlines — smooth in render, small in file. */
    private static final int ELLIPSE_SAMPLES = 48;

    private ScribbleShapeGeometry() {}

    /** @throws IllegalArgumentException on an unknown shape or unusable extent. */
    public static List<List<ScribblePoint>> outline(
            String shape, double x, double y, @Nullable Double x2, @Nullable Double y2) {
        String s = shape == null ? "" : shape.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "rectangle" -> List.of(rectangle(x, y, require(x2, "x2"), require(y2, "y2")));
            case "triangle" -> List.of(triangle(x, y, require(x2, "x2"), require(y2, "y2")));
            case "ellipse" -> List.of(ellipse(x, y, require(x2, "x2"), require(y2, "y2")));
            case "circle" -> List.of(circle(x, y, require(x2, "x2")));
            case "line" -> List.of(points(x, y, require(x2, "x2"), require(y2, "y2")));
            case "arrow" -> arrow(x, y, require(x2, "x2"), require(y2, "y2"));
            default ->
                throw new IllegalArgumentException(
                        "Unknown shape '" + shape + "' — use rectangle, triangle, ellipse, circle, line or arrow.");
        };
    }

    /**
     * Every shape is parameterised as {@code x, y} + {@code x2, y2}:
     * rectangle/triangle/ellipse use them as the two bounding-box corners,
     * circle takes the center and a radius ({@code x2}), line/arrow as the
     * start and end point. One convention instead of per-shape param sets —
     * the LLM schema stays flat.
     */
    private static List<ScribblePoint> rectangle(double x1, double y1, double x2, double y2) {
        double left = Math.min(x1, x2), right = Math.max(x1, x2);
        double top = Math.min(y1, y2), bottom = Math.max(y1, y2);
        requireExtent(right - left, bottom - top);
        return List.of(pt(left, top), pt(right, top), pt(right, bottom), pt(left, bottom), pt(left, top));
    }

    private static List<ScribblePoint> triangle(double x1, double y1, double x2, double y2) {
        double left = Math.min(x1, x2), right = Math.max(x1, x2);
        double top = Math.min(y1, y2), bottom = Math.max(y1, y2);
        requireExtent(right - left, bottom - top);
        // Isoceles: apex over the horizontal center, base on the bottom edge.
        double cx = (left + right) / 2;
        return List.of(pt(cx, top), pt(right, bottom), pt(left, bottom), pt(cx, top));
    }

    private static List<ScribblePoint> ellipse(double x1, double y1, double x2, double y2) {
        double left = Math.min(x1, x2), right = Math.max(x1, x2);
        double top = Math.min(y1, y2), bottom = Math.max(y1, y2);
        requireExtent(right - left, bottom - top);
        double cx = (left + right) / 2, cy = (top + bottom) / 2;
        double rx = (right - left) / 2, ry = (bottom - top) / 2;
        List<ScribblePoint> pts = new ArrayList<>(ELLIPSE_SAMPLES + 1);
        for (int i = 0; i <= ELLIPSE_SAMPLES; i++) {
            double a = Math.PI * 2 * i / ELLIPSE_SAMPLES;
            pts.add(pt(cx + Math.cos(a) * rx, cy + Math.sin(a) * ry));
        }
        return pts;
    }

    private static List<ScribblePoint> circle(double cx, double cy, double radius) {
        if (radius <= 0) throw new IllegalArgumentException("radius must be positive: " + radius);
        List<ScribblePoint> pts = new ArrayList<>(ELLIPSE_SAMPLES + 1);
        for (int i = 0; i <= ELLIPSE_SAMPLES; i++) {
            double a = Math.PI * 2 * i / ELLIPSE_SAMPLES;
            pts.add(pt(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius));
        }
        return pts;
    }

    /** Shaft plus a two-line head — three strokes, drawn as one arrow. */
    private static List<List<ScribblePoint>> arrow(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double dist = Math.hypot(dx, dy);
        if (dist < 2) throw new IllegalArgumentException("arrow needs two distinct points");
        // Head size grows with the arrow, capped so it stays an arrow.
        double head = Math.min(60, Math.max(12, dist / 4));
        double ux = dx / dist, uy = dy / dist;
        double hx = x2 - ux * head, hy = y2 - uy * head;
        // Head lines at ~28° off the shaft axis.
        double cos = Math.cos(Math.toRadians(28)), sin = Math.sin(Math.toRadians(28));
        double leftX = hx + (ux * cos - uy * sin) * head;
        double leftY = hy + (uy * cos + ux * sin) * head;
        double rightX = hx + (ux * cos + uy * sin) * head;
        double rightY = hy + (uy * cos - ux * sin) * head;
        return List.of(points(x1, y1, x2, y2), points(x2, y2, leftX, leftY), points(x2, y2, rightX, rightY));
    }

    private static List<ScribblePoint> points(double x1, double y1, double x2, double y2) {
        return List.of(pt(x1, y1), pt(x2, y2));
    }

    private static ScribblePoint pt(double x, double y) {
        return new ScribblePoint(round(x), round(y), 0.5);
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    private static void requireExtent(double w, double h) {
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("shape extent is degenerate (w=" + w + ", h=" + h + ")");
        }
    }

    private static double require(@Nullable Double v, String name) {
        if (v == null) throw new IllegalArgumentException(name + " is required for this shape");
        return v;
    }

    /** Sheet-raster bounds check for a whole point set — diagram ink stays on the page. */
    public static void assertInBounds(List<ScribblePoint> pts, ScribbleSize size) {
        for (ScribblePoint p : pts) {
            if (p.x() < 0 || p.y() < 0 || p.x() > size.w() || p.y() > size.h()) {
                throw new IllegalArgumentException("point (" + p.x() + ", " + p.y() + ") is outside the sheet raster "
                        + size.w() + "x" + size.h() + " — keep all shape points on the page");
            }
        }
    }
}
