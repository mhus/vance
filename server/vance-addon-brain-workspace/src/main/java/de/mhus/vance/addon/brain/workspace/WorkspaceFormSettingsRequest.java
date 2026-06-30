package de.mhus.vance.addon.brain.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/settings}.
 * The design-mode form-settings dialog: {@code single} (one record vs.
 * card list), the {@code runScript} onSave hook, whether a {@code session}
 * is created for that script, and the document {@code title}.
 */
public record WorkspaceFormSettingsRequest(
        boolean single,
        @Nullable String runScript,
        boolean session,
        @Nullable String title) {}
