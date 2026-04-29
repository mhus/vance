package de.mhus.vance.foot.command;

/**
 * What kind of value a slash-command argument expects. Drives the
 * tab-completion source: {@link #ENUM} expands locally from the spec's
 * {@code choices}; the dynamic kinds ({@link #PROCESS}, {@link #SESSION},
 * {@link #PROJECT}, {@link #PROJECT_GROUP}, {@link #SKILL}) describe what
 * the value should refer to so a future completer can fetch from the
 * brain — for now the completer renders them as a non-completing hint.
 */
public enum ArgKind {
    /** Free-form text — no completion offered. */
    FREE,
    /** Closed list of values, supplied via {@link ArgSpec#choices()}. */
    ENUM,
    /** A think-process name within the bound session. */
    PROCESS,
    /** A session id within the bound project. */
    SESSION,
    /** A project id within the bound tenant. */
    PROJECT,
    /** A project-group name within the bound tenant. */
    PROJECT_GROUP,
    /** A skill name available to the active process. */
    SKILL
}
