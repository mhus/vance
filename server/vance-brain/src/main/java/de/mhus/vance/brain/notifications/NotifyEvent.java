package de.mhus.vance.brain.notifications;

import de.mhus.vance.api.inbox.Criticality;
import org.jspecify.annotations.Nullable;

/**
 * One thing-to-tell-the-user, channel-agnostic.
 *
 * @param tenantId      the user's tenant
 * @param userId        recipient
 * @param inboxItemId   the item this notification is about (so the
 *                      client can fetch / link)
 * @param criticality   drives channel filtering (LOW typically only
 *                      WS, NORMAL+ also email/mobile)
 * @param title         short, list-friendly
 * @param body          longer; may be null for ultra-compact items
 * @param deepLink      optional client-side URI to open the item
 */
public record NotifyEvent(
        String tenantId,
        String userId,
        String inboxItemId,
        Criticality criticality,
        String title,
        @Nullable String body,
        @Nullable String deepLink) {
}
