/**
 * Python-execution DTOs — REST surface for the Cortex Web-UI's Python
 * runner. The underlying {@code execute_python} tool is LLM-facing;
 * this package exposes the same execution pipeline as REST endpoints
 * so the Web-UI Run button can drive it without going through the
 * LLM loop.
 *
 * <p>Backend implementation lives in {@code de.mhus.vance.brain.python}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.api.python;
