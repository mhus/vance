/**
 * Per-tool usage counters that feed the tool-surface budget's ranking.
 *
 * <p>Deliberately separate from Micrometer: tool name over ~340 tools is
 * borderline as a metric tag, and Prometheus data is not queryable from
 * the brain anyway — but the triage step has to read these numbers on
 * every turn. Same reasoning as {@code event_log} /
 * {@code magrathea_journal}.
 *
 * <p>Design: {@code planning/tool-surface-budget.md} §4.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.shared.toolusage;
