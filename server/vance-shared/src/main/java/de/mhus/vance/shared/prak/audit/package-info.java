/**
 * Prak audit-trail persistence.
 *
 * <p>{@link de.mhus.vance.shared.prak.audit.PrakRunRecord} is one row
 * per successful analyzer pass: trigger, window, sanitize counters,
 * strength override count, promotion outcome, model used, duration.
 * The MongoDB collection {@code prak_runs} feeds calibration analysis
 * (expected-item-count vs. actual over time, model-tier drift,
 * dedup/hard-cap pressure) and gives operators a single place to
 * look at "what did Prak just do" without scraping logs.
 *
 * <p>Records are flat — primitive fields only, no cross-package
 * type references. Wire records from {@link de.mhus.vance.shared.prak}
 * stay deliberately separate so the analyzer's data shape and the
 * persistence schema can evolve independently.
 */
@NullMarked
package de.mhus.vance.shared.prak.audit;

import org.jspecify.annotations.NullMarked;
