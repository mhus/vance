/**
 * Zaphod — minimal walking-skeleton {@link de.mhus.vance.brain.thinkengine.ThinkEngine}.
 *
 * <p>First concrete engine shipped. Its job is to exercise the plumbing
 * (registry, context, lane, LLM path, settings lookup) end-to-end with the
 * smallest possible surface: send a text, get a text back. Bleibt danach in
 * der Registry als Smoke-Test-Engine.
 *
 * <p>Spec: {@code specification/zaphod-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.zaphod;

import org.jspecify.annotations.NullMarked;
