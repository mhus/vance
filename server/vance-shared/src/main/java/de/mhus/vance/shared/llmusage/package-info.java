/**
 * Persistent usage + cost accounting for LLM calls.
 *
 * <p>Whereas {@code llmtrace} stores opt-in deep traces (content,
 * timing, every direction) for debugging, this package stores the
 * always-on cost ledger: tokens consumed and the price snapshot that
 * was in effect at call time. Reports under
 * {@code /brain/{tenant}/usage/...} aggregate these documents per
 * project, model, and time-bucket.
 *
 * <p>The price snapshot is verewigt on every write — later changes to
 * {@code ai-models.yaml} do not rewrite history.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.shared.llmusage;
