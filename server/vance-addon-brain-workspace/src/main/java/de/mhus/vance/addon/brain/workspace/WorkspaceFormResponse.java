package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workspace/form}.
 * Carries the edit-config's field schema, the target data file's
 * current values (flat {@code fieldName -> value} map) and the resolved
 * target path. Consumed by the {@code vance-form} block's editor view.
 *
 * <p>Not {@code @GenerateTypeScript}-annotated on purpose — the client
 * imports {@link FormFieldDto} from {@code @vance/generated} and defines
 * the slim response shape inline, avoiding generator handling of the
 * untyped {@code values} map.
 */
public record WorkspaceFormResponse(
        List<FormFieldDto> fields,
        String mode,
        Map<String, Object> values,
        List<Map<String, Object>> records,
        String target) {}
