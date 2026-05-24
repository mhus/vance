package de.mhus.vance.brain.slartibartfast.architect;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Schema-specific strategy for one {@link OutputSchemaType}. The
 * Slartibartfast engine (lifecycle, recovery, audit, inbox,
 * EXECUTING/EXECUTION_VALIDATING) stays schema-agnostic and looks
 * up a {@code SchemaArchitect} bean keyed by
 * {@link ArchitectState#getOutputSchemaType()} at the two
 * schema-aware seams:
 *
 * <ul>
 *   <li><b>PROPOSING</b> — the architect supplies the system
 *       prompt and (optionally) a sub-recipe listing block in the
 *       user prompt.</li>
 *   <li><b>VALIDATING</b> — the architect declares the expected
 *       {@code engine:} value, runs its own shape validators
 *       against the generated {@link RecipeDraft}, and contributes
 *       a schema-specific tail to the recovery-hint that the
 *       engine's generic re-prompt builder ships back to the
 *       LLM.</li>
 * </ul>
 *
 * <p>Adding a new schema is now an additive operation: one new
 * bean implementing this interface, no edits to ProposingPhase or
 * ValidatingPhase. See
 * {@code planning/slart-schema-architects-refactor.md} for the
 * refactor rationale.
 */
public interface SchemaArchitect {

    /** Which schema this architect handles. The Slart engine
     *  resolves the bean by this key — every bean MUST declare
     *  a distinct {@link OutputSchemaType}. */
    OutputSchemaType type();

    // ──────────────────── PROPOSING-side ────────────────────

    /** System-prompt fed into the PROPOSING LLM call. Carries the
     *  schema's YAML shape, output contract, and any
     *  schema-specific instructions. */
    String proposingSystemPrompt();

    /** Does this schema benefit from listing the project's
     *  available recipes in the user prompt? Returns true for
     *  Marvin (recipe names go into {@code allowedSubTaskRecipes})
     *  and Zaphod (each head's {@code recipe} field). Vogon
     *  doesn't need it — strategy phases name workers directly. */
    boolean wantsSubRecipeListing();

    /** Append schema-specific context to the PROPOSING user
     *  prompt — typically the sub-recipe listing block. Called
     *  after the generic subgoals/criteria block, before the
     *  closing "now emit a JSON object" instruction. {@code
     *  availableRecipes} is non-empty only when
     *  {@link #wantsSubRecipeListing()} returned true and the
     *  project has recipes to list. */
    void appendProposingContext(
            StringBuilder sb,
            ArchitectState state,
            List<ResolvedRecipe> availableRecipes);

    // ──────────────────── VALIDATING-side ────────────────────

    /** The string the {@code engine:} top-level field must carry
     *  in a recipe of this schema — used by VALIDATING's generic
     *  shape check before delegating to the architect. */
    String expectedEngineName();

    /** Run the schema-specific shape validators against the
     *  generated draft. Implementations:
     *  <ul>
     *    <li>append every check (pass + fail) to {@code report},
     *        so the audit chain shows the full validator
     *        coverage;</li>
     *    <li>return the FIRST failing check (or {@code null}
     *        when everything passed) — the engine uses it to
     *        short-circuit further generic checks and drive the
     *        recovery loop.</li>
     *  </ul>
     *  {@code recipeMap} is the YAML pre-parsed into a Java map
     *  by VALIDATING's generic step — implementations don't need
     *  to parse the YAML twice. {@code process} is provided so
     *  the architect can look up project state (e.g.
     *  Marvin/Zaphod listing recipes via {@code RecipeLoader}). */
    @Nullable
    ValidationCheck validateDraftShape(
            RecipeDraft draft,
            Map<String, Object> recipeMap,
            ThinkProcessDocument process,
            List<ValidationCheck> report);

    /** Schema-specific tail appended to the VALIDATING-recovery
     *  hint shipped back to the PROPOSING LLM. The engine's
     *  generic builder already includes the failure summary and
     *  the valid sg-id list; this method adds the "emit a
     *  corrected recipe with engine: X, params.Y …" instruction
     *  shape for the specific schema. */
    String recoveryHintTail(ThinkProcessDocument process);

    /** Does VALIDATING's generic substring-based path-persistence
     *  check apply to this schema? The check (in
     *  {@code ValidatingPhase}) reads each acceptance criterion
     *  that names a file-path and verifies the recipe yaml
     *  contains a {@code doc_write_text} / {@code doc_create_text}
     *  call against that path. Returns true by default — accurate
     *  for Vogon (the strategyPlanYaml carries workerInputs with
     *  tool calls) and Marvin (the promptPrefix's KIND blocks
     *  carry taskSpec with paths). Zaphod overrides to false
     *  because its outputs are produced by spawned head
     *  sub-processes and the synthesizer turn — neither runs
     *  through tool calls embedded in the Slart-emitted recipe,
     *  so the substring check would always fail by construction. */
    default boolean wantsPathPersistenceCheck() {
        return true;
    }

    /** Extracts the final recipe YAML from the LLM's PROPOSING
     *  JSON response.
     *
     *  <p>Default implementation reads a top-level {@code yaml}
     *  string field — used by Vogon and Zaphod, which let the
     *  LLM emit YAML directly.
     *
     *  <p>{@link MarvinArchitect} overrides this to read template
     *  parameters from a {@code params} object and render a
     *  bundled YAML template, eliminating LLM creativity on the
     *  recipe-structural level. Hard-fails (throws) when required
     *  fields are missing.
     *
     *  @throws RuntimeException on missing / invalid input — the
     *          ProposingPhase converts this into a re-prompt with
     *          the exception message as the correction hint. */
    default String extractRecipeYaml(java.util.Map<String, Object> jsonRoot) {
        Object y = jsonRoot.get("yaml");
        if (!(y instanceof String yaml) || yaml.isBlank()) {
            throw new IllegalArgumentException(
                    "required field 'yaml' missing or blank");
        }
        return yaml;
    }
}
