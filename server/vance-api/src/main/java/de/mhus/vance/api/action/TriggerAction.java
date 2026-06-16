package de.mhus.vance.api.action;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Unified action shape for every trigger surface — scheduler, event,
 * workflow-task, LLM tool, manual REST call. Exactly one variant
 * describes what the trigger spawns or runs.
 *
 * <p>Sealed hierarchy so executors pattern-match on the concrete
 * variant and the YAML disjunction ({@code recipe:} | {@code script:} |
 * {@code workflow:}) maps directly to the type system.
 *
 * <p>Shell-Commands sind kein TriggerAction — sie sind ein
 * Workflow-Task-Konzept ({@code shell_task} nach Rename) mit eigener
 * Workspace-Sequenzierung und ExitCode-Outcome-Mapping.
 *
 * <p>Design rationale: {@code specification/trigger-actions.md} §3.
 */
public sealed interface TriggerAction
        permits TriggerAction.Recipe,
                TriggerAction.Script,
                TriggerAction.Workflow {

    /**
     * User identity the action runs as. {@code null} means
     * "trigger-default" (e.g. scheduler's {@code createdBy}, event's
     * authenticated caller, workflow's owner). Executors resolve it.
     */
    @Nullable String runAs();

    /**
     * Free-form parameters forwarded to the spawned target. For
     * events, the HTTP body is additionally mounted under
     * {@code params.payload} before parsing.
     */
    @Nullable Map<String, Object> params();

    // ──────────────────── Recipe ────────────────────

    /**
     * Spawn a ThinkProcess. Two paths:
     *
     * <ul>
     *   <li><b>Recipe-driven:</b> {@code recipe} set, {@code engineOverride}
     *       null. Resolved through the {@code RecipeResolver} cascade
     *       (project → _vance → bundled). Engine + params + prompt-prefix
     *       come from the recipe.</li>
     *   <li><b>Engine-direct:</b> {@code engineOverride} set, {@code recipe}
     *       null. No recipe lookup; engine is resolved by name and
     *       caller-supplied {@code params} apply directly.</li>
     * </ul>
     *
     * <p>The two paths are <b>mutually exclusive</b> — exactly one of
     * {@code recipe} / {@code engineOverride} must be non-blank.
     *
     * <p>Most callers use the minimal-form factory {@link #of(String,
     * String, Map, String)} which fills the spawn-detail fields with
     * sensible defaults. Spawn-tools (process_create, process_run,
     * session-bootstrap) use the canonical constructor to supply
     * caller-controlled process name, title, goal, profile, etc.
     */
    record Recipe(
            @Nullable String recipe,
            @Nullable String engineOverride,
            @Nullable String processName,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String inheritContextLevel,
            @Nullable String connectionProfile,
            @Nullable String initialMessage,
            @Nullable Map<String, Object> params,
            @Nullable String runAs) implements TriggerAction {

        public Recipe {
            boolean hasRecipe = recipe != null && !recipe.isBlank();
            boolean hasEngine = engineOverride != null && !engineOverride.isBlank();
            if (hasRecipe == hasEngine) {
                throw new IllegalArgumentException(
                        "TriggerAction.Recipe: exactly one of recipe or engineOverride "
                                + "must be non-blank (recipe='" + recipe
                                + "', engineOverride='" + engineOverride + "')");
            }
        }

        /**
         * Minimal-form factory for callers that only need the
         * recipe-driven path with caller-merged params: scheduler,
         * event, workflow-task, parser. Spawn-detail fields default to
         * {@code null} — the executor auto-generates the process name
         * and derives the connection profile from the trigger kind.
         */
        public static Recipe of(
                String recipe,
                @Nullable String initialMessage,
                @Nullable Map<String, Object> params,
                @Nullable String runAs) {
            return new Recipe(
                    recipe,
                    /*engineOverride*/ null,
                    /*processName*/ null,
                    /*title*/ null,
                    /*goal*/ null,
                    /*inheritContextLevel*/ null,
                    /*connectionProfile*/ null,
                    initialMessage,
                    params,
                    runAs);
        }
    }

    // ──────────────────── Script ────────────────────

    /**
     * Execute a JavaScript file via {@code ScriptExecutor}. Source
     * resolution follows {@link ScriptSource} — {@code DOCUMENT}
     * reads from the document cascade, {@code WORKSPACE} reads from
     * an existing RootDir.
     *
     * <p>{@code dirName} is required iff {@link #source()} is
     * {@link ScriptSource#WORKSPACE}; {@code null} otherwise.
     *
     * <p>Sandbox scope (read-only vs. tool-call-capable) is decided
     * by the executor based on the calling trigger — see
     * {@code specification/trigger-actions.md} §8.
     */
    record Script(
            ScriptSource source,
            @Nullable String dirName,
            String path,
            @Nullable Integer timeoutSeconds,
            @Nullable Map<String, Object> params,
            @Nullable String runAs) implements TriggerAction {

        public Script {
            if (source == null) {
                throw new IllegalArgumentException("Script.source must be set");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Script.path must be non-blank");
            }
            if (source == ScriptSource.WORKSPACE && (dirName == null || dirName.isBlank())) {
                throw new IllegalArgumentException(
                        "Script.dirName is required when source=WORKSPACE");
            }
            if (source == ScriptSource.DOCUMENT && dirName != null && !dirName.isBlank()) {
                throw new IllegalArgumentException(
                        "Script.dirName must be null when source=DOCUMENT");
            }
            if (timeoutSeconds != null && timeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "Script.timeoutSeconds must be > 0 when set");
            }
        }
    }

    // ──────────────────── Workflow ────────────────────

    /**
     * Spawn a Magrathea workflow run. The workflow is resolved through
     * the normal workflow cascade (project → _vance → bundled).
     */
    record Workflow(
            String workflow,
            @Nullable Map<String, Object> params,
            @Nullable String runAs) implements TriggerAction {

        public Workflow {
            if (workflow == null || workflow.isBlank()) {
                throw new IllegalArgumentException("Workflow.workflow must be non-blank");
            }
        }
    }
}
