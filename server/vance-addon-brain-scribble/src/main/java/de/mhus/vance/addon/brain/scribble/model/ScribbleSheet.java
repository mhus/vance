package de.mhus.vance.addon.brain.scribble.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A full {@code kind: scribble} document — one handwriting sheet:
 * display metadata plus the sheet raster and the ordered stroke list.
 */
public record ScribbleSheet(@Nullable String title, ScribbleSize size, List<ScribbleStroke> strokes) {

    public ScribbleSheet {
        strokes = List.copyOf(strokes);
    }

    public static ScribbleSheet empty(@Nullable String title) {
        return new ScribbleSheet(title, ScribbleSize.a4Portrait(), List.of());
    }

    public ScribbleSheet withStrokes(List<ScribbleStroke> newStrokes) {
        return new ScribbleSheet(title, size, newStrokes);
    }

    public ScribbleSheet withTitle(@Nullable String newTitle) {
        return new ScribbleSheet(newTitle, size, strokes);
    }
}
