package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/page}. Only
 * {@code title} is required; {@code slug} defaults to the slugged title,
 * {@code section} defaults to the workspace root (top-level page).
 */
@GenerateTypeScript("workspace")
public record WorkspaceCreatePageRequest(
        String title,
        @Nullable String description,
        @Nullable String section,
        @Nullable String slug) {}
