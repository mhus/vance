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
 * <p>Design rationale: {@code planning/trigger-actions.md} §3.
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
     * Spawn a ThinkProcess via a recipe. The recipe is resolved
     * through the normal {@code RecipeResolver} cascade (project →
     * _vance → bundled).
     */
    record Recipe(
            String recipe,
            @Nullable String initialMessage,
            @Nullable Map<String, Object> params,
            @Nullable String runAs) implements TriggerAction {

        public Recipe {
            if (recipe == null || recipe.isBlank()) {
                throw new IllegalArgumentException("Recipe.recipe must be non-blank");
            }
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
     * {@code planning/trigger-actions.md} §8.
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
