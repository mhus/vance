package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/schema}.
 * The design-mode form builder sends the full field list; the service
 * replaces the document's {@code $meta.form.fields} and re-syncs the
 * native {@code schema} column list. Form-level settings (single, onSave,
 * title) are set separately via {@code /form/settings}.
 */
public record WorkspaceFormSchemaRequest(
        List<FormFieldDto> fields) {}
