package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Slim document projection — one entry of a workspace document search
 * (link picker). Carries enough metadata for the picker to show a
 * meaningful row (title, kind badge, path) and build a
 * {@code vance:/<path>?kind=<kind>} URI.
 */
@GenerateTypeScript("workspace")
public record WorkspaceDocumentItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
