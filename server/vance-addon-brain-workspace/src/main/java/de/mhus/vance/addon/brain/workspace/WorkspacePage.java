package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One discovered WorkPage inside a workspace folder. {@code section}
 * is the leaf-folder name of the page's parent (relative to the
 * workspace root) — empty string means top-level.
 *
 * <p>{@code rebuildScripts} are the scripts the page opted into running
 * on {@code app_rebuild} (declared in its {@code $meta.rebuildScripts});
 * empty when the page declares none.
 */
public record WorkspacePage(
        DocumentDocument doc,
        String relativePath,
        String section,
        String title,
        @Nullable String description,
        @Nullable String icon,
        @Nullable Double sortIndex,
        List<String> rebuildScripts) {
}
