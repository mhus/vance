package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One parsed view, ready to render.
 *
 * <p>{@link #notes} carries the problems that do <b>not</b> stop the page.
 * The distinction is the whole design of this type: a view document that
 * cannot be parsed is an error and never becomes a {@code RenderedView}, but a
 * button pointing at a view that no longer exists, or a table the manifest
 * stopped declaring, must still render. Refusing the page for those would mean
 * one stale handle takes down an app the reader was using — the same late
 * binding an inter-link handle gets, where a dead target opens the app and
 * stays put.
 *
 * @param handle the view's handle.
 * @param title  the view's caption: the manifest's title, else the document's.
 * @param root   the widget tree.
 * @param notes  soft problems, shown alongside the page rather than instead
 *               of it.
 */
@GenerateTypeScript("bistromath")
public record RenderedView(
        String handle,
        @Nullable String title,
        ViewNode root,
        List<String> notes) {

    public RenderedView {
        if (notes == null) notes = List.of();
    }
}
