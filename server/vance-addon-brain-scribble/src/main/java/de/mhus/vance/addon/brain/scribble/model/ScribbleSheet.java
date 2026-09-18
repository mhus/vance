package de.mhus.vance.addon.brain.scribble.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A full {@code kind: scribble} document — one handwriting sheet: display
 * metadata plus the sheet raster and the ordered stroke list, and two
 * book-level flags the sheet carries itself.
 *
 * <p>{@code enabled} (default {@code true}) keeps a sheet in the
 * scribblebook PDF export; {@code defaultSheet} (default {@code false})
 * marks the sheet a scribblebook opens when no {@code ?entry=} asks for a
 * specific one. Both serialize only on deviation — an ordinary sheet's
 * body does not change with this model. On disk the default flag is the
 * key {@code default}; the Java/wire name is {@code defaultSheet} because
 * {@code default} is a Java keyword.
 */
public record ScribbleSheet(
        @Nullable String title,
        ScribbleSize size,
        List<ScribbleStroke> strokes,
        boolean enabled,
        boolean defaultSheet) {

    public ScribbleSheet {
        strokes = List.copyOf(strokes);
    }

    /** Back-compat constructor for flagless call sites: defaults apply. */
    public ScribbleSheet(@Nullable String title, ScribbleSize size, List<ScribbleStroke> strokes) {
        this(title, size, strokes, true, false);
    }

    public static ScribbleSheet empty(@Nullable String title) {
        return new ScribbleSheet(title, ScribbleSize.a4Portrait(), List.of(), true, false);
    }

    public ScribbleSheet withStrokes(List<ScribbleStroke> newStrokes) {
        return new ScribbleSheet(title, size, newStrokes, enabled, defaultSheet);
    }

    public ScribbleSheet withTitle(@Nullable String newTitle) {
        return new ScribbleSheet(newTitle, size, strokes, enabled, defaultSheet);
    }

    public ScribbleSheet withEnabled(boolean newEnabled) {
        return new ScribbleSheet(title, size, strokes, newEnabled, defaultSheet);
    }

    public ScribbleSheet withDefaultSheet(boolean newDefaultSheet) {
        return new ScribbleSheet(title, size, strokes, enabled, newDefaultSheet);
    }
}
