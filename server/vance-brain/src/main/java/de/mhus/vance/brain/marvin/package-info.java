/**
 * Marvin — the deep-think engine. Runs a persistent, dynamically
 * growing task-tree (Mongo-backed) and orchestrates execution through
 * synchronous PLAN / AGGREGATE LLM-calls and asynchronous WORKER /
 * USER_INPUT external wait-points.
 *
 * <p>See {@code specification/marvin-engine.md} for the full subsystem
 * spec; tree storage and traversal helpers live in
 * {@code de.mhus.vance.shared.marvin}.
 */
@NullMarked
package de.mhus.vance.brain.marvin;

import org.jspecify.annotations.NullMarked;
