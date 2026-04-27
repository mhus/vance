package de.mhus.vance.shared.inbox;

/**
 * Published by {@link InboxItemService} after a new item lands in
 * the database. The notification dispatcher subscribes to fan it out
 * over the configured channels (WS push, email, mobile push).
 *
 * <p>Auto-answered LOW items publish this event AND
 * {@link InboxItemAnsweredEvent} immediately — listeners that don't
 * want to ping the user about an item that's already resolved should
 * filter on {@code item.status} themselves.
 */
public record InboxItemCreatedEvent(InboxItemDocument item) {
}
