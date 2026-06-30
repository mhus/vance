package de.mhus.vance.addon.brain.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Request for {@code POST /brain/{tenant}/addon/workspace/input/settings}:
 * the design-mode {@code onSave} config written into the bound document's
 * front-matter header. {@code runScript} is a {@code .js} document path
 * (relative to the doc folder); a blank value clears the hook.
 */
public record WorkspaceInputSettingsRequest(
        @Nullable String runScript,
        boolean session) {}
