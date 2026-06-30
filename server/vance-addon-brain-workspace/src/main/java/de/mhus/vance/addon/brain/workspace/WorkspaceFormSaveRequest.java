package de.mhus.vance.addon.brain.workspace;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/save}.
 * Single-mode forms send {@code values} (a flat {@code fieldName -> value}
 * map); records-mode forms send {@code records} (a list of such maps).
 * The service picks the side that matches the edit-config's {@code mode}.
 */
public record WorkspaceFormSaveRequest(
        @Nullable Map<String, Object> values,
        @Nullable List<Map<String, Object>> records) {}
