/**
 * Vance hub Activity-Log — append-only, user-scoped, recap-friendly.
 * Records what each Vance process has been up to so its peers (other
 * hub-sessions of the same user) can produce a sensible Bootstrap-Recap
 * and stay loosely synchronised. Vance-exclusive — other engines neither
 * write nor read here. See {@code specification/vance-engine.md} §5.2.
 */
@NullMarked
package de.mhus.vance.brain.eddie.activity;

import org.jspecify.annotations.NullMarked;
