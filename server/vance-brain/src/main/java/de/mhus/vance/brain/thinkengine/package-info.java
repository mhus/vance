/**
 * Think-engine registry and lifecycle plumbing.
 *
 * <p>A {@link ThinkEngine} is the algorithm (Spring bean); a
 * {@link de.mhus.vance.shared.thinkprocess.ThinkProcessDocument} is a
 * running instance. {@link ThinkEngineService} discovers all engine beans at
 * startup, routes lifecycle calls to them, and builds a fresh
 * {@link ThinkEngineContext} per invocation so engines never cache runtime
 * state themselves.
 *
 * <p>The {@link ThinkEngineContext} API starts small: it exposes the
 * primitives Ford needs ({@code llm()}, {@code settings()}) and stubs the
 * rest ({@code chat()}, {@code events()}, {@code drainPending()},
 * {@code memory()}, {@code tools()}, {@code processes()}). Those sub-APIs
 * land when the corresponding bricks are in place.
 */
@NullMarked
package de.mhus.vance.brain.thinkengine;

import org.jspecify.annotations.NullMarked;
