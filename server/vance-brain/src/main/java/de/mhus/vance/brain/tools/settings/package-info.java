/**
 * LLM tool surface for the settings cascade's write side. Small and
 * deliberately guarded: every write is ADMIN-checked against the same
 * {@code Resource.Setting} shape the admin REST controller uses, agent-write
 * rules W1 and W3 apply, and plain values only — credentials keep flowing
 * through human surfaces.
 */
@NullMarked
package de.mhus.vance.brain.tools.settings;

import org.jspecify.annotations.NullMarked;
