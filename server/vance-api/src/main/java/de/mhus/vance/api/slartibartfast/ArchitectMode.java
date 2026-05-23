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
    /** Default — Slart authors a new recipe. PERSISTING writes
     *  to {@code _user/<recipeName>.yaml} when named, else to
     *  the legacy sandbox {@code _slart/<runId>/<llm-name>.yaml}. */
    CREATE,

    /** Slart modifies an existing user-namespace recipe. The
     *  target name is on {@link ArchitectState#getTargetRecipeName()};
     *  the LOADING_EXISTING phase loads + parses it before
     *  PROPOSING. PERSISTING overwrites the original path with
     *  the patched yaml; the audit chain stores the previous
     *  yaml under {@code previousRecipeYaml} for rollback. */
    EDIT,
}
