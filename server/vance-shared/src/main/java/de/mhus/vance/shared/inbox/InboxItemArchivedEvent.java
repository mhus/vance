package de.mhus.vance.shared.inbox;

/**
 * Published when an item is archived. Currently only used for UI
 * counter updates; no dispatcher action.
 */
public record InboxItemArchivedEvent(InboxItemDocument item) {
}
