/**
 * Starting OS processes on the brain: one entry point ({@link ProcessLauncher})
 * so every child gets the same sealed environment — and later the same isolation
 * and resource caps — instead of each starter building its own
 * {@code ProcessBuilder}.
 */
@NullMarked
package de.mhus.vance.shared.process;

import org.jspecify.annotations.NullMarked;
