package de.mhus.vance.shared.inbox;

/**
 * Published when an item transitions to
 * {@link de.mhus.vance.api.inbox.InboxItemStatus#ANSWERED}. The
 * brain-side router consumes this to build a
 * {@code SteerMessage.InboxAnswer} and append it to the originating
 * process's pending queue, waking its lane.
 */
public record InboxItemAnsweredEvent(InboxItemDocument item) {
}
