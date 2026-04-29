/**
 * Vance — the personal hub engine that lives in the per-user Home
 * project (kind {@code SYSTEM}). Vance is the always-on Jarvis-style
 * dialogue partner from which the user creates, observes, and steers
 * regular projects. See {@code specification/vance-engine.md}.
 *
 * <p>Phase-2 implementation delegates the conversational machinery to
 * {@link de.mhus.vance.brain.arthur.ArthurEngine} — Vance differs only
 * in identity, greeting, and tool cut. The hub-specific mechanics
 * (Activity-Log, Peer-Notifications) land in phase 4.
 */
@NullMarked
package de.mhus.vance.brain.eddie;

import org.jspecify.annotations.NullMarked;
