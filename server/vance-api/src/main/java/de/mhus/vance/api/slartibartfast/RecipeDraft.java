package de.mhus.vance.api.slartibartfast;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Output of the PROPOSING phase — a candidate recipe ready to be
 * validated and persisted. The {@link #getYaml()} string is the
 * verbatim content that will land at
 * {@code recipes/_slart/<runId>/<name>.yaml} once VALIDATING
 * passes. {@link #getJustifications()} maps each constraint key
 * (e.g. {@code "params.allowedSubTaskRecipes"}) to the
 * {@link Subgoal#getId()} that motivates it — the audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipeDraft {

    /** Recipe name without the {@code _slart/<runId>/} prefix —
     *  the templateName the recipe was rendered from
     *  (e.g. {@code "essay-pipeline"}). The full storage path is
     *  derived as
     *  {@code recipes/_slart/<runId>/<name>.yaml}. */
    private String name = "";

    @Builder.Default
    private OutputSchemaType outputSchemaType = OutputSchemaType.VOGON_STRATEGY;

    /** Verbatim YAML content. Parser-validated in VALIDATING. */
    private String yaml = "";

    /** Per-constraint audit trail. Key is a dotted path into the
     *  recipe's structure, value is the {@link Subgoal#getId()} that
     *  motivates it. Example:
     *  <pre>{@code
     *  "params.allowedSubTaskRecipes" → "sg2"
     *  "params.allowedExpandDocumentRefPaths" → "sg3"
     *  "promptPrefix" → "sg1"
     *  }</pre>
     *  Validator rejects entries pointing at unknown subgoal-ids. */
    @Builder.Default
    private Map<String, String> justifications = new LinkedHashMap<>();

    /** Confidence the planner assigns to this draft (0.0..1.0).
     *  Heuristic — proportion of non-speculative subgoals. */
    private double confidence;

    /** Free-text notes the planner wants to surface to the caller
     *  (or to the user in case of escalation). {@code null} when
     *  no warnings apply. */
    private @Nullable String warnings;

    /** Foreign key into {@link ArchitectState#getRationales()} —
     *  why this overall recipe shape (template choice, phase
     *  ordering, constraint set). Distinct from
     *  {@link #getJustifications()}, which justifies individual
     *  constraints; this rationale covers the meta-decision. */
    private @Nullable String shapeRationaleId;
}
