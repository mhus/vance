/**
 * Brain-side wizard subsystem — YAML loader, cascade resolution, and
 * REST endpoints for {@code /brain/{tenant}/wizards/...}. See
 * {@code specification/wizards.md} for the full subsystem spec.
 *
 * <p>Wizards produce text only — the rendered prompt lands in the
 * Web-UI chat input. Spawn / lifecycle / tool-routing is unaffected.
 */
@NullMarked
package de.mhus.vance.brain.wizard;

import org.jspecify.annotations.NullMarked;
