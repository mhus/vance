package de.mhus.vance.shared.inbox;

/**
 * Published by {@link InboxItemService#updateContent} when the
 * body, title or payload of an existing item is patched in-place
 * by a system component (e.g. Fook updating its tracker item with
 * the upstream ticket URL after a successful transfer).
 *
 * <p>Listeners may want to re-notify the assignee — e.g. flag the
 * item as "new content available". The default Inbox WS push
 * channel re-sends the item snapshot so connected UIs refresh.
 */
public record InboxItemUpdatedEvent(InboxItemDocument item) {
}
