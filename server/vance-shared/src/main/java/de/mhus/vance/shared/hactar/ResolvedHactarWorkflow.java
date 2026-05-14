package de.mhus.vance.shared.hactar;

import de.mhus.vance.api.hactar.HactarWorkflowSource;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of loading and parsing one workflow YAML document.
 *
 * <p>{@code name} comes from the document path, not from any
 * {@code name:} field inside the body — matches the recipe/scheduler
 * convention. {@code yaml} carries the raw body so that GET endpoints
 * and editor round-trip preserve the author's formatting and comments.
 *
 * <p>{@code documentId} is the Mongo {@code _id} of the underlying
 * document, needed to update it through the document layer.
 * {@code createdBy} is the document's author — surfaced for audit and
 * usable as a fallback caller identity for system-triggered starts
 * (scheduler/hook).
 *
 * <p>See plan §3.1 for the YAML schema and §12 for the cascade rules.
 */
public record ResolvedHactarWorkflow(
        String name,
        String yaml,
        HactarWorkflowSource source,
        @Nullable String documentId,
        @Nullable String createdBy,
        @Nullable String description,
        @Nullable String version,
        String startState,
        Map<String, HactarParameterSpec> parameters,
        Map<String, HactarStateSpec> states,
        HactarBoundsSpec bounds,
        List<String> allowedTools,
        List<String> tags) {
}
