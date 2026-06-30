package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/schema}.
 * The design-mode form builder sends the full field list; the service
 * replaces the edit-config's {@code form.fields} with it.
 */
public record WorkspaceFormSchemaRequest(
        List<FormFieldDto> fields) {}
