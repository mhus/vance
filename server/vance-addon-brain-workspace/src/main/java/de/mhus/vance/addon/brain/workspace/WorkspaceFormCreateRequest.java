package de.mhus.vance.addon.brain.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/create}.
 * Creates a new edit-config skeleton in the app folder. {@code name} is
 * slugified into the file names; {@code title} is the display title
 * (falls back to {@code name}).
 */
public record WorkspaceFormCreateRequest(
        String name,
        @Nullable String title) {}
