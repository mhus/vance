/**
 * Persistent LLM-roundtrip trace records — the durable mirror of the
 * {@code de.mhus.vance.brain.ai.trace} log channel for tenants that
 * opt-in via the {@code tracing.llm} setting (cascade tenant →
 * project → think-process).
 *
 * <p>One {@link de.mhus.vance.shared.llmtrace.LlmTraceDocument} per leg
 * (input message / output message / tool call / tool result), grouped
 * by {@code turnId}. Retention is TTL-bounded at 90 days. The Insights
 * UI surfaces these via {@link de.mhus.vance.shared.llmtrace.LlmTraceService}.
 */
@NullMarked
package de.mhus.vance.shared.llmtrace;

import org.jspecify.annotations.NullMarked;
