/**
 * Hactar — script-architect engine. Generates JavaScript
 * orchestrator scripts from a high-level goal: drafts code via LLM,
 * validates with the parse-only {@code JsValidationService},
 * recovers on syntax errors, and persists the accepted body via
 * {@code doc_write_text}.
 *
 * <p>See {@code planning/hactar-engine.md} for the lifecycle
 * and {@code specification/script-engine.md} §3.5 for the surrounding
 * header convention the generated scripts conform to.
 */
@NullMarked
package de.mhus.vance.brain.hactar;

import org.jspecify.annotations.NullMarked;
