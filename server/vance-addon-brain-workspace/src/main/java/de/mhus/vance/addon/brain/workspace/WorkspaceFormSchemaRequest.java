package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/schema}.
 * The design-mode form builder sends the full field list plus the
 * {@code single} flag; the service replaces the document's
 * {@code $meta.form.fields} + {@code single} and re-syncs {@code schema}.
 */
public record WorkspaceFormSchemaRequest(
        List<FormFieldDto> fields,
        boolean single) {}
