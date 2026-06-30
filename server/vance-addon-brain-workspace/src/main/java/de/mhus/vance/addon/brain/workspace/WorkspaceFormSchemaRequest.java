package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workspace/form/schema}.
 * The design-mode form builder sends the full field list plus the form
 * {@code mode} ({@code single} | {@code records}); the service replaces
 * the edit-config's {@code form.fields} and {@code mode}.
 */
public record WorkspaceFormSchemaRequest(
        List<FormFieldDto> fields,
        @Nullable String mode) {}
