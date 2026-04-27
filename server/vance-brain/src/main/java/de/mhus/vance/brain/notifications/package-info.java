/**
 * Notification subsystem — generic Dispatcher + Channel pattern.
 * v1: WS-Push to active connections. v2: Email + Mobile Push (stubs
 * present, {@code canHandle} always {@code false}).
 *
 * <p>See {@code specification/user-interaction.md} §12.
 */
@NullMarked
package de.mhus.vance.brain.notifications;

import org.jspecify.annotations.NullMarked;
