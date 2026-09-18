package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Server-side rasterisation of a {@link ScribbleSheet} to PNG — the building
 * block behind the {@code scribble_sheet_image} vision tool and, later, the
 * OCR track (see {@code planning/scribble.md} §10/§16).
 *
 * <p>Fidelity bar is <i>legible</i>, not pixel-identical to the browser: the
 * client draws with perfect-freehand, this renders variable-width segments
 * with round caps — the same width math ({@code size * (0.4 + 1.2 * pressure)},
 * the thinning curve of {@code getStroke(thinning: 0.6)}) at a coarser
 * geometry. Handwriting stays fully readable; anti-aliasing keeps thin pen
 * strokes from breaking up.
 *
 * <p>Sheet coordinates are 150&nbsp;dpi-native (A4 = 1240×1754), so the
 * default render needs no resampling — {@code dpi} scales the raster for
 * finer handwriting, {@code region} crops to a sheet-space rectangle.
 */
public final class ScribbleRenderer {

    /** The dpi the sheet coordinate system is defined in. */
    public static final int NATIVE_DPI = 150;

    /**
     * Palette mirror of the client's {@code ink.ts PALETTE} — index → RGB.
     * Unknown indices fall back to black, same as the client.
     */
    private static final Map<String, Color> PALETTE = Map.of(
            "1", new Color(0x11, 0x18, 0x27),
            "2", new Color(0xdc, 0x26, 0x26),
            "3", new Color(0x25, 0x63, 0xeb),
            "4", new Color(0x16, 0xa3, 0x4a));

    /** Pen base widths in sheet units — mirror of {@code ink.ts WIDTH_PX}. */
    private static final Map<ScribbleStroke.Width, Double> BASE_WIDTH =
            Map.of(ScribbleStroke.Width.S, 2.5, ScribbleStroke.Width.M, 5.0, ScribbleStroke.Width.L, 11.0);

    /** A sheet-space crop rectangle. */
    public record Region(int x, int y, int w, int h) {}

    private ScribbleRenderer() {}

    /**
     * Renders the sheet (or a region of it) to PNG.
     *
     * @param sheet  the parsed sheet model
     * @param dpi    target resolution; {@code 150} renders 1:1 in sheet units
     * @param region crop in sheet coordinates, {@code null} for the full sheet
     * @throws IllegalArgumentException if the region does not intersect the
     *                                  sheet raster
     */
    /**
     * Renders the sheet (or a region of it) as a buffered image — the PDF
     * path consumes this directly (no PNG encode/decode round-trip);
     * {@link #renderPng} wraps it with PNG encoding for the wire.
     *
     * @param sheet  the parsed sheet model
     * @param dpi    target resolution; {@code 150} renders 1:1 in sheet units
     * @param region crop in sheet coordinates, {@code null} for the full sheet
     * @throws IllegalArgumentException if the region does not intersect the
     *                                  sheet raster
     */
    public static BufferedImage renderImage(ScribbleSheet sheet, int dpi, @Nullable Region region) {
        ScribbleSize size =
                sheet.size() != null && sheet.size().isPositive() ? sheet.size() : ScribbleSize.a4Portrait();
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be positive: " + dpi);
        Region crop = clampRegion(region, size);
        if (crop.w() <= 0 || crop.h() <= 0) {
            throw new IllegalArgumentException("Region does not intersect the sheet raster");
        }
        double scale = dpi / (double) NATIVE_DPI;
        int imgW = Math.max(1, (int) Math.round(crop.w() * scale));
        int imgH = Math.max(1, (int) Math.round(crop.h() * scale));

        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgW, imgH);
            g.scale(scale, scale);
            g.translate(-crop.x(), -crop.y());
            drawInk(g, sheet);
        } finally {
            g.dispose();
        }

        return image;
    }

    /**
     * Renders the sheet (or a region of it) to PNG bytes — the wire format
     * of the vision tool and the OCR input.
     */
    public static byte[] renderPng(ScribbleSheet sheet, int dpi, @Nullable Region region) throws IOException {
        BufferedImage image = renderImage(sheet, dpi, region);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG encoder available");
        }
        return out.toByteArray();
    }

    private static void drawInk(Graphics2D g, ScribbleSheet sheet) {
        for (ScribbleStroke stroke : sheet.strokes()) {
            if (stroke.tool() != ScribbleStroke.Tool.PEN) continue;
            g.setColor(PALETTE.getOrDefault(stroke.color(), PALETTE.get("1")));
            double base = BASE_WIDTH.getOrDefault(stroke.width(), BASE_WIDTH.get(ScribbleStroke.Width.M));
            List<ScribblePoint> pts = stroke.points();
            if (pts.isEmpty()) continue;
            if (pts.size() == 1) {
                dot(g, pts.get(0), widthAt(base, pts.get(0).pressure()));
                continue;
            }
            for (int i = 1; i < pts.size(); i++) {
                var a = pts.get(i - 1);
                var b = pts.get(i);
                double w = widthAt(base, (a.pressure() + b.pressure()) / 2.0);
                g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(a.x(), a.y(), b.x(), b.y());
            }
        }
    }

    /** perfect-freehand thinning curve ({@code thinning: 0.6}) at segment granularity. */
    private static double widthAt(double base, double pressure) {
        double w = base * (0.4 + 1.2 * clamp01(pressure));
        return Math.max(0.4 * base, Math.min(1.6 * base, w));
    }

    private static void dot(Graphics2D g, ScribblePoint p, double w) {
        double r = w / 2.0;
        g.fillOval((int) Math.round(p.x() - r), (int) Math.round(p.y() - r), (int) Math.round(w), (int) Math.round(w));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Region clampRegion(@Nullable Region region, ScribbleSize size) {
        if (region == null) return new Region(0, 0, size.w(), size.h());
        int x1 = Math.max(0, region.x());
        int y1 = Math.max(0, region.y());
        int x2 = Math.min(size.w(), region.x() + region.w());
        int y2 = Math.min(size.h(), region.y() + region.h());
        return new Region(x1, y1, x2 - x1, y2 - y1);
    }
}
