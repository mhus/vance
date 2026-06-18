/**
 * {@code documents}-Channel implementation: subscribe/unsubscribe per
 * document path plus per-recipient presence-roster pushes.
 *
 * <p>First real use of the multi-channel surface introduced by the
 * Live-WS Foundation. v1 carries presence only — change-push
 * (server fan-out of {@code DocumentChangedEvent}) is the natural
 * follow-up step on the same subscriber registry.
 *
 * <p>See {@code planning/document-presence.md} for the design.
 */
@NullMarked
package de.mhus.vance.brain.ws.documents;

import org.jspecify.annotations.NullMarked;
