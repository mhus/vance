package de.mhus.vance.shared.inbox;

/**
 * Published by {@link MaximegalonService} after a contribution was appended
 * to a thread.
 *
 * <p>Published only for the write that landed — never for the author's own
 * badge, never twice. Listeners that must not react to a particular author
 * (a Trillian that would otherwise wake itself with its own answer) filter
 * on {@link #message()}{@code .getAuthorUserId()} themselves; Maximegalon
 * knows nothing about its readers.
 *
 * <p>This is the machine-facing counterpart to what the badge says to a
 * human. Deliberately without effect on the notification dispatcher: a
 * message in a thread is not, by itself, a notification — who has to hear
 * about it is a decision each listener makes (see
 * {@code specification/public/maximegalon-system.md} §10).
 */
public record MaximegalonMessageEvent(MaximegalonDocument item, MaximegalonMessage message) {}
