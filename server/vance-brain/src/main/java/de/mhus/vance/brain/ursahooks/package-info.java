/**
 * Hook subsystem — event-driven outbound reactions configured as YAML
 * documents under {@code _vance/hooks/<event>/<name>.yaml}. Hooks come
 * in two flavours, {@code js} (GraalJS) and {@code llm} (Pebble prompt
 * + declarative action schema), both restricted to a small host-API
 * surface (HTTP-post, inbox-create, structured logging). Hooks never
 * spawn processes and never call agent tools.
 *
 * <p>See {@code specification/ursahooks.md} for the design.
 */
@NullMarked
package de.mhus.vance.brain.ursahooks;

import org.jspecify.annotations.NullMarked;
