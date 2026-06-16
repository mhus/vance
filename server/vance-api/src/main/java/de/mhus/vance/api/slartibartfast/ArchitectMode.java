package de.mhus.vance.api.slartibartfast;

/**
 * Whether a Slartibartfast run creates a new recipe or modifies
 * an existing one. Determined by the FRAMING-LLM from the user
 * description ("Erstelle ein …" → CREATE; "Erweitere 'X' um …"
 * → EDIT). Drives the LOADING_EXISTING phase, the PROPOSING
 * system-prompt switch ("invent" vs. "patch"), and the
 * PERSISTING write-path.
 *
 * <p>See {@code planning/slart-as-project-architect.md} §D-4.
 */
public enum ArchitectMode {
    /** Default — Slart authors a new artefact from scratch.
     *  PERSISTING writes to {@code _user/<recipeName>.<ext>} when
     *  the user named the artefact, else to the sandbox bucket
     *  {@code _slart/<runId>/<llm-name>.<ext>}. */
    CREATE,

    /** Slart modifies an existing user-namespace recipe **in
     *  place**. The target name is on
     *  {@link ArchitectState#getTargetRecipeName()};
     *  LOADING_EXISTING loads + parses it before PROPOSING.
     *  PERSISTING overwrites the original path with the patched
     *  yaml; the audit chain stores the previous yaml under
     *  {@code previousRecipeYaml} for rollback. Recipe-only —
     *  scripts use {@link #UPDATE} instead. */
    EDIT,

    /** Slart produces a new version of an existing artefact while
     *  preserving its structure, **without overwriting** the
     *  original. The existing artefact is referenced by
     *  {@link ArchitectState#getExistingScriptRef()};
     *  LOADING_EXISTING populates
     *  {@link ArchitectState#getExistingScriptCode()} with the
     *  current body and (optionally)
     *  {@link ArchitectState#getFailureReason()} with the prior
     *  Hactar-FAILED reason that triggered this update. PERSISTING
     *  writes the new body to a fresh sandbox bucket
     *  {@code _slart/<runId>/...} — the caller (Cortex, an inbox
     *  action, …) decides whether to copy it over the original
     *  or keep it as a variant. v1: SCRIPT_JS only; recipe-UPDATE
     *  is the open point in
     *  {@code specification/slartibartfast-engine.md} §11. */
    UPDATE,
}
