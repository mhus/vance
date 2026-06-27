package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.shared.document.DocumentDocument;
import org.jspecify.annotations.Nullable;

/**
 * One discovered Canvas-Page inside a workspace folder. {@code section}
 * is the leaf-folder name of the page's parent (relative to the
 * workspace root) — empty string means top-level.
 */
public record WorkspacePage(
        DocumentDocument doc,
        String relativePath,
        String section,
        String title,
        @Nullable String description,
        @Nullable String icon,
        @Nullable Double sortIndex) {
}
