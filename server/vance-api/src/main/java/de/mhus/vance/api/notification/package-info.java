/**
 * Wire-contract types for the user-notification side-channel — a short
 * attention-grabbing ping ("beep, process done") that's flüchtig, makes
 * a sound on the client, and is independent of the
 * {@code process-progress} stream (live status, no audio) and the inbox
 * (persistent items with answer lifecycle).
 *
 * <p>See {@code specification/user-notification-channel.md}.
 */
@NullMarked
package de.mhus.vance.api.notification;

import org.jspecify.annotations.NullMarked;
