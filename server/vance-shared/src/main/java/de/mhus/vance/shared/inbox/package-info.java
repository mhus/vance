/**
 * Persistence layer for the user-interaction (inbox) subsystem.
 * Mongo documents, repository, service. Wire-format types live in
 * {@code de.mhus.vance.api.inbox}; brain-side handlers and the
 * notification dispatcher live in {@code de.mhus.vance.brain.inbox}
 * and {@code de.mhus.vance.brain.notifications}.
 *
 * <p>See {@code specification/user-interaction.md}.
 */
@NullMarked
package de.mhus.vance.shared.inbox;

import org.jspecify.annotations.NullMarked;
