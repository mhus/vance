/**
 * Persistence layer for engine-to-engine and client-to-engine messages.
 *
 * <p>Each {@link EngineMessageDocument} captures one message from a sender
 * process to a target process across its full lifecycle — from sender insert
 * (outbox), through delivery to the receiver's inbox, to drainage by the
 * receiver's lane. Replaces the embedded
 * {@code ThinkProcessDocument.pendingMessages} list as the message bus
 * once the migration completes.
 *
 * <p>Routing details, ack protocol, and crash-recovery semantics are
 * specified in {@code specification/engine-message-routing.md}.
 *
 * <p>Access goes through {@link EngineMessageService}; the repository is
 * package-private.
 */
@NullMarked
package de.mhus.vance.shared.enginemessage;

import org.jspecify.annotations.NullMarked;
