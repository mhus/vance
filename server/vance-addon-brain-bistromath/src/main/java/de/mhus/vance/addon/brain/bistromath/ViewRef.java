package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One view the runtime **found**, not one the manifest declared.
 *
 * <p>A view is a document carrying {@code $meta.kind: app-view} anywhere under
 * the app folder. Its handle is the file name without extension — which makes
 * the handle the app's own stable identity for the view, and the
 * {@code ?entry=} target of an [inter-link], without a second name to keep in
 * sync.
 *
 * @param handle file name without extension; a slug, because it lands in a URL
 *               and in an {@code AppTarget}.
 * @param path   the document path, so the client can deep-link to the source.
 * @param title  the document's title, for the view switcher. {@code null} when
 *               the document has none — the handle is then the label.
 */
@GenerateTypeScript("bistromath")
public record ViewRef(
        String handle,
        String path,
        @Nullable String title) {
}
