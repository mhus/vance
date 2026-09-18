package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The agent's drawing surface on a handwriting sheet. Handwriting stays
 * user input — these tools add <b>diagram ink</b> (geometric outlines,
 * generic polylines) that renders exactly like drawn ink, and delete it
 * again. A house outline is {@code scribble_shape_add} with a
 * {@code triangle} roof on a {@code rectangle}; nothing is hand-built YAML.
 *
 * <p>All mutations go through {@link ScribbleService#writeSheet} (one
 * writer, one codec). Points live in sheet coordinates (A4 @150 dpi:
 * 1240×1754, origin top-left, y grows downward) and must stay on the
 * raster — an off-page point fails the whole call instead of producing
 * invisible ink.
 */
@Component
public class ScribbleDrawTools {

    private ScribbleDrawTools() {}

    /** Adds the outline strokes of one geometric shape. */
    @Component
    public static class ShapeAddTool extends AbstractDrawTool {

        private static final Map<String, Object> SCHEMA = Map.of(
                "type",
                "object",
                "properties",
                new LinkedHashMap<String, Object>() {
                    {
                        put("path", Map.of("type", "string", "description", "Scribble document path to draw on."));
                        put("projectId", Map.of("type", "string"));
                        put(
                                "shape",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of("rectangle", "triangle", "ellipse", "circle", "line", "arrow"),
                                        "description",
                                        "Shape to draw. rectangle/triangle/ellipse: x,y and x2,y2 are "
                                                + "opposite bounding-box corners. circle: x,y is the center, x2 "
                                                + "the radius. line/arrow: from x,y to x2,y2."));
                        put("x", Map.of("type", "number", "description", "First x in sheet coordinates."));
                        put("y", Map.of("type", "number", "description", "First y in sheet coordinates."));
                        put("x2", Map.of("type", "number", "description", "Second x / radius / end x."));
                        put("y2", Map.of("type", "number", "description", "Second y / end y."));
                        put(
                                "color",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Palette index \"1\"–\"4\" (black, red, blue, green)."));
                        put("width", Map.of("type", "string", "enum", List.of("s", "m", "l")));
                    }
                },
                "required",
                List.of("path", "shape", "x", "y", "x2"));

        public ShapeAddTool(
                EddieContext eddieContext, DocumentService documentService, ScribbleService scribbleService) {
            super(eddieContext, documentService, scribbleService, "scribble_shape_add", SCHEMA);
        }

        @Override
        public String description() {
            return "Draw a geometric shape (rectangle, triangle, ellipse, circle, line, arrow) "
                    + "as ink on a handwriting sheet. Coordinates are sheet units (A4 = 1240x1754, "
                    + "origin top-left, y grows down). A shape appends one to three strokes — an "
                    + "arrow is shaft plus head. Nothing is off-page: every point must be on the raster.";
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            ScribbleToolSupport.Resolved resolved = resolve(params, ctx);
            ScribbleSheet sheet = read(resolved);
            String shape = ScribbleToolSupport.paramString(params, "shape");
            Double x = paramDouble(params, "x");
            Double y = paramDouble(params, "y");
            Double x2 = paramDouble(params, "x2");
            Double y2 = paramDouble(params, "y2");
            if (shape == null || x == null || y == null || x2 == null) {
                throw new ToolException("shape, x, y and x2 are required");
            }
            ScribbleStroke.Tool tool = ScribbleStroke.Tool.PEN;
            ScribbleStroke.Width width = width(params);
            String color = color(params);

            List<List<ScribblePoint>> outlines;
            try {
                outlines = ScribbleShapeGeometry.outline(shape, x, y, x2, y2);
                for (List<ScribblePoint> pts : outlines) {
                    ScribbleShapeGeometry.assertInBounds(pts, effectiveSize(sheet));
                }
            } catch (IllegalArgumentException e) {
                throw new ToolException(e.getMessage(), e);
            }

            int firstIndex = sheet.strokes().size();
            List<ScribbleStroke> next = new ArrayList<>(sheet.strokes());
            for (List<ScribblePoint> pts : outlines) {
                next.add(new ScribbleStroke(tool, color, width, pts));
            }
            scribbleService.writeSheet(resolved.doc(), withStrokes(sheet, next), ctx.userId());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", resolved.doc().getPath());
            out.put("strokesAdded", outlines.size());
            out.put("firstStrokeIndex", firstIndex);
            out.put(
                    "note",
                    "Shape drawn — the user sees it on the sheet immediately (live view). "
                            + "Remove it with scribble_stroke_delete(path, index).");
            return out;
        }
    }

    /** Adds one generic polyline stroke from explicit points. */
    @Component
    public static class StrokeAddTool extends AbstractDrawTool {

        private static final Map<String, Object> SCHEMA = Map.of(
                "type",
                "object",
                "properties",
                new LinkedHashMap<String, Object>() {
                    {
                        put("path", Map.of("type", "string", "description", "Scribble document path to draw on."));
                        put("projectId", Map.of("type", "string"));
                        put(
                                "points",
                                Map.of(
                                        "type",
                                        "array",
                                        "description",
                                        "Polyline points as [[x, y], …] in sheet coordinates (A4 = 1240x1754, "
                                                + "origin top-left). At least 2 points, all on the raster.",
                                        "items",
                                        Map.of("type", "array", "items", Map.of("type", "number"))));
                        put(
                                "color",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Palette index \"1\"–\"4\" (black, red, blue, green)."));
                        put("width", Map.of("type", "string", "enum", List.of("s", "m", "l")));
                    }
                },
                "required",
                List.of("path", "points"));

        public StrokeAddTool(
                EddieContext eddieContext, DocumentService documentService, ScribbleService scribbleService) {
            super(eddieContext, documentService, scribbleService, "scribble_stroke_add", SCHEMA);
        }

        @Override
        public String description() {
            return "Draw one polyline stroke on a handwriting sheet from explicit points "
                    + "[[x, y], …] in sheet coordinates. Prefer scribble_shape_add for anything "
                    + "geometric — this tool is for freeform diagram ink that no shape covers. "
                    + "All points must be on the raster (A4 = 1240x1754, origin top-left).";
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            ScribbleToolSupport.Resolved resolved = resolve(params, ctx);
            ScribbleSheet sheet = read(resolved);
            Object raw = params.get("points");
            if (!(raw instanceof List<?> list) || list.size() < 2) {
                throw new ToolException("points must be an array of at least 2 [x, y] pairs");
            }
            List<ScribblePoint> pts = new ArrayList<>();
            try {
                for (Object o : list) {
                    if (!(o instanceof List<?> pair) || pair.size() < 2) {
                        throw new ToolException("each point must be [x, y]");
                    }
                    pts.add(new ScribblePoint(requireInt(pair.get(0)), requireInt(pair.get(1)), 0.5));
                }
            } catch (NumberFormatException e) {
                throw new ToolException("points must be numbers: " + e.getMessage(), e);
            }
            try {
                ScribbleShapeGeometry.assertInBounds(pts, effectiveSize(sheet));
            } catch (IllegalArgumentException e) {
                throw new ToolException(e.getMessage(), e);
            }

            ScribbleStroke stroke = new ScribbleStroke(ScribbleStroke.Tool.PEN, color(params), width(params), pts);
            int index = sheet.strokes().size();
            List<ScribbleStroke> next = new ArrayList<>(sheet.strokes());
            next.add(stroke);
            scribbleService.writeSheet(resolved.doc(), withStrokes(sheet, next), ctx.userId());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", resolved.doc().getPath());
            out.put("strokeIndex", index);
            return out;
        }
    }

    /** Removes one stroke by index — the agent's own eraser for its shapes. */
    @Component
    public static class StrokeDeleteTool extends AbstractDrawTool {

        private static final Map<String, Object> SCHEMA = Map.of(
                "type",
                "object",
                "properties",
                new LinkedHashMap<String, Object>() {
                    {
                        put("path", Map.of("type", "string", "description", "Scribble document path."));
                        put("projectId", Map.of("type", "string"));
                        put(
                                "index",
                                Map.of(
                                        "type",
                                        "integer",
                                        "description",
                                        "Index of the stroke to remove (see scribble_validate findings)."));
                    }
                },
                "required",
                List.of("path", "index"));

        public StrokeDeleteTool(
                EddieContext eddieContext, DocumentService documentService, ScribbleService scribbleService) {
            super(eddieContext, documentService, scribbleService, "scribble_stroke_delete", SCHEMA);
        }

        @Override
        public String description() {
            return "Remove one stroke from a handwriting sheet by its index — the way to undo "
                    + "a shape you drew. Indices are stable between read and delete unless another "
                    + "stroke was added or removed in between; scribble_validate lists strokes by index.";
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            ScribbleToolSupport.Resolved resolved = resolve(params, ctx);
            ScribbleSheet sheet = read(resolved);
            Object raw = params.get("index");
            if (!(raw instanceof Number n)) {
                throw new ToolException("index must be an integer");
            }
            int index = n.intValue();
            if (index < 0 || index >= sheet.strokes().size()) {
                throw new ToolException("index " + index + " is out of range — the sheet has "
                        + sheet.strokes().size() + " strokes");
            }
            List<ScribbleStroke> next = new ArrayList<>(sheet.strokes());
            next.remove(index);
            scribbleService.writeSheet(resolved.doc(), withStrokes(sheet, next), ctx.userId());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", resolved.doc().getPath());
            out.put("remainingStrokes", next.size());
            return out;
        }
    }

    // ── Shared plumbing ────────────────────────────────────────────

    abstract static class AbstractDrawTool implements Tool {
        private final String name;
        private final Map<String, Object> schema;
        protected final EddieContext eddieContext;
        protected final DocumentService documentService;
        protected final ScribbleService scribbleService;

        AbstractDrawTool(
                EddieContext eddieContext,
                DocumentService documentService,
                ScribbleService scribbleService,
                String name,
                Map<String, Object> schema) {
            this.eddieContext = eddieContext;
            this.documentService = documentService;
            this.scribbleService = scribbleService;
            this.name = name;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean primary() {
            return false;
        }

        @Override
        public Set<String> labels() {
            return Set.of("eddie", "write", "document", "scribble");
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return schema;
        }

        protected ScribbleToolSupport.Resolved resolve(Map<String, Object> params, ToolInvocationContext ctx) {
            return ScribbleToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        }

        protected ScribbleSheet read(ScribbleToolSupport.Resolved resolved) {
            return scribbleService.readSheet(resolved.doc());
        }

        protected static ScribbleSheet withStrokes(ScribbleSheet sheet, List<ScribbleStroke> strokes) {
            return sheet.withStrokes(strokes);
        }

        protected static ScribbleStroke.Width width(Map<String, Object> params) {
            return ScribbleStroke.Width.parse(ScribbleToolSupport.paramString(params, "width"), ScribbleStroke.Width.M);
        }

        /** Palette index with the same fallback the renderer uses. */
        protected static String color(Map<String, Object> params) {
            String c = ScribbleToolSupport.paramString(params, "color");
            return c != null ? c : "1";
        }

        protected static @Nullable Double paramDouble(Map<String, Object> params, String key) {
            Object v = params.get(key);
            return v instanceof Number n ? n.doubleValue() : null;
        }

        protected static int requireInt(Object v) {
            if (v instanceof Number n) return (int) Math.round(n.doubleValue());
            throw new NumberFormatException(String.valueOf(v));
        }

        protected static ScribbleSize effectiveSize(ScribbleSheet sheet) {
            return sheet.size() != null && sheet.size().isPositive() ? sheet.size() : ScribbleSize.a4Portrait();
        }
    }
}
