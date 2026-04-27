/**
 * Wire-contract types for the user-interaction (inbox) subsystem.
 * Item types, status, criticality, and the answer payload schema are
 * defined here; client implementations consume them. Persistent
 * documents live in {@code de.mhus.vance.shared.inbox}.
 *
 * <p>See {@code specification/user-interaction.md} for the full
 * subsystem spec.
 */
@NullMarked
package de.mhus.vance.api.inbox;

import org.jspecify.annotations.NullMarked;
