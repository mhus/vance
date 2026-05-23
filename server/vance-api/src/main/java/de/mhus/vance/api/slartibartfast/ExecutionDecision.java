package de.mhus.vance.api.slartibartfast;

/**
 * Output of the {@link ArchitectStatus#EXECUTION_PLANNING} phase
 * — Slart's verdict on whether to execute the freshly persisted
 * recipe, and with what prompt.
 *
 * <p>The decision is driven by signals in the user's original
 * description (explicit "frage 'X'" / "kein Test" hints), the
 * schema type of the recipe (pipeline vs. architecture), and
 * whether the user provided a concrete mission. See
 * {@code planning/slart-as-project-architect.md} §D-3 for the
 * full decision tree.
 */
public enum ExecutionDecision {
    /** Run the recipe with the user's original description as
     *  goal. Default for pipeline schemas (Vogon, Marvin) when
     *  the description names a concrete mission. */
    USE_USER_PROMPT,

    /** Run the recipe with a Slart-LLM-generated test prompt.
     *  Used when the user asked for a test ("teste mal mit …",
     *  "und versuch's mit …") and supplied a specific test
     *  question, OR when the user signalled "smoke test" without
     *  specifics and the architecture schema benefits from a
     *  trivial verification. */
    USE_GENERATED_PROMPT,

    /** Do not run the recipe — Slart finishes with DONE right
     *  after PERSISTING. Default for architecture schemas
     *  (Zaphod, later PROJECT_SETUP) and for explicit user
     *  signals like "nur anlegen", "nicht ausführen", "kein
     *  Test". */
    SKIP,
}
