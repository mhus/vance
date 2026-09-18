package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps between the typed {@link ScribbleSheet} model and the flat wire
 * DTOs. The {@code fromDto} direction routes through
 * {@link ScribbleCodec#strokeFromMap} so validation and leniency live in
 * one place — the DTO is never trusted past the codec grammar.
 */
public final class ScribbleDtoMapper {

    private ScribbleDtoMapper() {
        // utility class
    }

    // ── model → dto ───────────────────────────────────────────────

    public static ScribbleSheetDto toDto(ScribbleSheet sheet) {
        List<ScribbleStrokeDto> strokes = new ArrayList<>();
        for (ScribbleStroke stroke : sheet.strokes()) strokes.add(strokeToDto(stroke));
        return new ScribbleSheetDto(
                sheet.title(), sheet.size().w(), sheet.size().h(), strokes, sheet.enabled(), sheet.defaultSheet());
    }

    private static ScribbleStrokeDto strokeToDto(ScribbleStroke stroke) {
        List<List<Double>> points = new ArrayList<>();
        for (ScribblePoint p : stroke.points()) {
            points.add(List.of((double) p.x(), (double) p.y(), p.pressure()));
        }
        return new ScribbleStrokeDto(
                stroke.tool().wire(), stroke.color(), stroke.width().wire(), points);
    }

    // ── dto → model ───────────────────────────────────────────────

    public static ScribbleSheet fromDto(ScribbleSheetDto dto) {
        List<ScribbleStroke> strokes = new ArrayList<>();
        if (dto.strokes() != null) {
            for (ScribbleStrokeDto s : dto.strokes()) {
                ScribbleStroke stroke = ScribbleCodec.strokeFromMap(strokeToMap(s));
                if (stroke != null) strokes.add(stroke);
            }
        }
        ScribbleSize size = new ScribbleSize(dto.sizeW(), dto.sizeH());
        return new ScribbleSheet(
                dto.title(),
                size.isPositive() ? size : ScribbleSize.a4Portrait(),
                strokes,
                dto.enabled(),
                dto.defaultSheet());
    }

    private static Map<String, Object> strokeToMap(ScribbleStrokeDto s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", s.tool());
        m.put("c", s.color());
        m.put("w", s.width());
        List<Object> points = new ArrayList<>();
        if (s.points() != null) points.addAll(s.points());
        m.put("p", points);
        return m;
    }
}
