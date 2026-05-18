/**
 * Script Cortex backend — Web-UI-driven script editor + executor.
 * Owns the {@code /brain/{tenant}/scripts/*} REST surface, the async
 * execution service with cancel + log streaming, and the deep-validate
 * LLM review. See {@code planning/script-cortex.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.script.cortex;
