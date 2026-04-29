/**
 * Tool-type registry. Each {@link
 * de.mhus.vance.brain.tools.types.ToolFactory} is a Spring bean that
 * expands a persisted {@link
 * de.mhus.vance.shared.servertool.ServerToolDocument} into a runnable
 * {@link de.mhus.vance.brain.tools.Tool}. Types are code; instances
 * (server tools) are configuration.
 */
@NullMarked
package de.mhus.vance.brain.tools.types;

import org.jspecify.annotations.NullMarked;
