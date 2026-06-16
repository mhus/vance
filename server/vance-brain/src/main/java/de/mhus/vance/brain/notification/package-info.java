/**
 * Server-side emission path for the user-notification side-channel —
 * {@link de.mhus.vance.brain.notification.NotificationService} is the
 * single place that builds {@link de.mhus.vance.api.notification.NotificationDto}
 * envelopes and routes them through
 * {@link de.mhus.vance.brain.events.ClientEventPublisher}.
 *
 * <p>Distinct from {@link de.mhus.vance.brain.progress.ProgressEmitter}
 * (live status, no audio) and from the inbox (persistent items). See
 * {@code specification/user-notification-channel.md}.
 */
@NullMarked
package de.mhus.vance.brain.notification;

import org.jspecify.annotations.NullMarked;
