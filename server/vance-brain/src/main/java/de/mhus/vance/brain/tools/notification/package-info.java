/**
 * Server-tool surface for the user-notification side-channel —
 * {@link de.mhus.vance.brain.tools.notification.NotifyTool} exposes
 * {@code vance_notify} to the LLM tool loop, sitting on top of
 * {@link de.mhus.vance.brain.notification.NotificationService}. See
 * {@code specification/user-notification-channel.md}.
 */
@NullMarked
package de.mhus.vance.brain.tools.notification;

import org.jspecify.annotations.NullMarked;
