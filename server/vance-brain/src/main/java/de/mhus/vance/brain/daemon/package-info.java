/**
 * Foot-daemon registry — tracks {@code profile=daemon} connections
 * project-scoped and per-pod, so cross-session tool routing can find
 * the right WS to forward an invoke to. See
 * {@code planning/foot-daemon-tools.md} for the architecture overview.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.daemon;
