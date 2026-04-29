/**
 * Server-tool runtime — bridges {@link
 * de.mhus.vance.shared.servertool.ServerToolDocument} (persistence) to
 * {@link de.mhus.vance.brain.tools.Tool} (runtime). The
 * {@link de.mhus.vance.brain.servertool.ServerToolService} owns the
 * cascade lookup ({@code project → _vance → built-in beans}) and the
 * write path; {@code ConfiguredToolSource} plugs the cascade into
 * {@link de.mhus.vance.brain.tools.ToolDispatcher}.
 *
 * <p>Other services <b>must</b> go through {@code ServerToolService} —
 * direct {@link de.mhus.vance.shared.servertool.ServerToolRepository}
 * use bypasses cascade and label semantics.
 */
@NullMarked
package de.mhus.vance.brain.servertool;

import org.jspecify.annotations.NullMarked;
