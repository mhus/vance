package de.mhus.vance.addon.brain.workspace;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/save}.
 * The form data is always a list of records ({@code items}); a single-mode
 * form just sends a one-element list.
 */
public record WorkspaceFormSaveRequest(
        @Nullable List<Map<String, Object>> records,
        @Nullable List<String> schema) {}
