package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Slim document projection — one hit of {@code GET
 * /brain/{tenant}/documents/search}. Carries enough metadata for a picker
 * to show a meaningful row (title, kind badge, path) and to build a
 * {@code vance:/<path>?kind=<kind>} URI.
 */
@GenerateTypeScript("documents")
public record DocumentSearchItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
