package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Structural checks for a {@code kind: scribble} sheet — the single
 * validator behind both {@code kind_validate} and the later
 * {@code scribble_validate} tool. Pure and content-based: no services,
 * no IO, shared {@link Finding} vocabulary.
 *
 * <p>The codec is lenient (unusable strokes are dropped, never thrown),
 * so this is where the loss becomes visible: a second pass over the raw
 * body reports every dropped stroke by index — reporting the count alone
 * would not help anyone repair the file.
 */
public final class ScribbleValidationService {

    /**
     * Edge tolerance for the bounds check. Hands overshoot the page edge
     * by a few points all the time; warning on every such point would make
     * the validator noise. Only points clearly outside the raster count.
     */
    private static final int BOUNDS_TOLERANCE = 8;

    private ScribbleValidationService() {
        // utility class
    }

    /**
     * Validate a scribble body. Structural parse failures surface as
     * {@link KindCodecException} — the caller maps them to a parse-error
     * finding.
     */
    public static List<Finding> validate(String content, @Nullable String mimeType, String location) {
        Map<String, Object> top = ScribbleCodec.parseTop(content, mimeType);
        ScribbleSheet sheet = ScribbleCodec.parseFlat(top);

        List<Finding> findings = new ArrayList<>();
        reportDroppedStrokes(top, location, findings);
        reportOutOfBoundsPoints(sheet, location, findings);
        return findings;
    }

    /** Raw second pass: every stroke the lenient codec would drop, by index. */
    private static void reportDroppedStrokes(Map<String, Object> top, String location, List<Finding> findings) {
        List<Map<String, Object>> rawStrokes = ScribbleCodec.rawStrokes(top);
        for (int i = 0; i < rawStrokes.size(); i++) {
            if (ScribbleCodec.strokeFromMap(rawStrokes.get(i)) == null) {
                findings.add(Finding.error(
                        location,
                        "scribble-stroke-dropped",
                        "strokes[" + i + "] has no usable points and would be dropped on the next save."));
            }
        }
    }

    private static void reportOutOfBoundsPoints(ScribbleSheet sheet, String location, List<Finding> findings) {
        int minX = -BOUNDS_TOLERANCE;
        int minY = -BOUNDS_TOLERANCE;
        int maxX = sheet.size().w() + BOUNDS_TOLERANCE;
        int maxY = sheet.size().h() + BOUNDS_TOLERANCE;
        for (int i = 0; i < sheet.strokes().size(); i++) {
            ScribbleStroke stroke = sheet.strokes().get(i);
            for (ScribblePoint p : stroke.points()) {
                if (p.x() < minX || p.y() < minY || p.x() > maxX || p.y() > maxY) {
                    findings.add(Finding.warning(
                            location,
                            "scribble-bounds",
                            "strokes[" + i + "] runs off the sheet raster at (" + p.x() + ", " + p.y()
                                    + ") — the ink is stored, but viewers may clip it."));
                    break;
                }
            }
        }
    }
}
