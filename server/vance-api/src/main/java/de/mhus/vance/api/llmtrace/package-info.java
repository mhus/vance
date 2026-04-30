/**
 * Wire-contract DTOs for the persistent LLM-trace subsystem. Mirrors
 * {@code de.mhus.vance.shared.llmtrace.LlmTraceDocument} for transport
 * to the Insights UI; persistent documents live in
 * {@code de.mhus.vance.shared.llmtrace}.
 *
 * <p>The {@code direction} field is carried as a lower-case string
 * (e.g. {@code "input"}, {@code "output"}, {@code "tool_call"},
 * {@code "tool_result"}) — the canonical enum
 * ({@code de.mhus.vance.shared.llmtrace.LlmTraceDirection}) is not
 * exposed on the wire to keep {@code vance-api} free of cross-module
 * dependencies.
 */
@NullMarked
package de.mhus.vance.api.llmtrace;

import org.jspecify.annotations.NullMarked;
