package de.mhus.vance.addon.brain.workspace;

import java.util.Map;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/save}.
 * The {@code values} are a flat {@code fieldName -> value} map mirroring
 * the form's {@code FormFieldDto.name}s (value = String / List for
 * multi-select / List-of-Maps for repeat).
 */
public record WorkspaceFormSaveRequest(
        Map<String, Object> values) {}
