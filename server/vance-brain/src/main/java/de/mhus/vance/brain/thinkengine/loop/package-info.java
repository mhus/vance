/**
 * Safety nets for the Ford-shaped tool loops (Ford, Wowbagger, the
 * session-mode identities over {@code AbstractEngineSessionLoop}).
 *
 * <p>A turn ends by natural stop; there is no routine round cap. The nets
 * measure being <em>stuck</em> — the same tool batch repeating, an empty
 * reply, a failed model call — plus a generous per-turn wallclock as the
 * last resort, and an opt-in {@code maxIterations} for recipes whose bound
 * is intentional. What an engine does with a {@link
 * de.mhus.vance.brain.thinkengine.loop.SafetyStop} (close a worker, park a
 * chat) stays the engine's call. See {@code specification/public/ford-engine.md} §4a.
 */
@NullMarked
package de.mhus.vance.brain.thinkengine.loop;

import org.jspecify.annotations.NullMarked;
