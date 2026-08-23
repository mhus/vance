package de.mhus.vance.api.megadodo;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How loudly a feed row should present itself. Read by the UI for
 * highlighting and (later) by retention for keeping serious rows longer.
 *
 * <p>Separate from {@code outcome}: a tool that Agrajag disabled is a
 * {@link #WARN} without being a failed run, and a scheduler run that
 * failed is an {@link #ERROR} with {@code outcome = failure}.
 */
@GenerateTypeScript("megadodo")
public enum MegadodoSeverity {

    /** Normal operation — the bulk of the feed. */
    INFO,

    /** Worth noticing, nothing broke. Tool disabled, run skipped. */
    WARN,

    /** Something did not work. The case the feed exists for. */
    ERROR
}
