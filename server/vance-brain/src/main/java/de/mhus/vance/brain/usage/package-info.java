/**
 * Usage / cost aggregation REST surface.
 *
 * <p>{@link de.mhus.vance.brain.usage.LlmUsageReportService} runs
 * {@code $group} pipelines over {@code llm_usage_records} (written by
 * {@code LlmCallTracker} → {@code LlmUsageService}) to produce time-
 * bucketed and key-bucketed summaries for the Insights "Usage & Cost"
 * tab.
 *
 * <p>{@link de.mhus.vance.brain.usage.LlmUsageReportController} exposes
 * the service under {@code /brain/{tenant}/usage/*}. Tenant scoping
 * is enforced via {@code RequestAuthority.ADMIN} on every endpoint.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.usage;
