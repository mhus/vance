package de.mhus.vance.addon.brain.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Response for {@code GET /brain/{tenant}/addon/workspace/input}: the
 * editable text body of the bound document (the front-matter header, if any,
 * is stripped) plus the {@code onSave} config read from that header
 * ({@code onSave} script path + {@code session} flag).
 */
public record WorkspaceInputResponse(
        String content,
        @Nullable String onSaveScript,
        boolean onSaveSession) {}
