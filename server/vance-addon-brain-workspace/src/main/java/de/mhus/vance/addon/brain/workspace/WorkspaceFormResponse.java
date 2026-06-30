package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workspace/form}.
 * Carries the data document's {@code $meta.form} field schema, the
 * {@code single} flag and the current {@code items} records, plus the
 * document path. Consumed by the {@code vance-form} block's editor view.
 *
 * <p>Not {@code @GenerateTypeScript}-annotated on purpose — the client
 * imports {@link FormFieldDto} from {@code @vance/generated} and defines
 * the slim response shape inline, avoiding generator handling of the
 * untyped record maps.
 */
public record WorkspaceFormResponse(
        List<FormFieldDto> fields,
        boolean single,
        List<Map<String, Object>> records,
        String target,
        @Nullable String title,
        @Nullable String onSaveScript,
        boolean onSaveSession) {}
