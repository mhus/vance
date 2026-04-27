package de.mhus.vance.shared.inbox;

/**
 * Published when an item's {@code assignedToUserId} changes via
 * delegation. Notification dispatcher uses this to ping the new
 * assignee.
 */
public record InboxItemDelegatedEvent(
        InboxItemDocument item,
        String previousAssignedToUserId) {
}
