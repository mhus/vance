package de.mhus.vance.brain.notifications;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audit-log entry per channel-attempt for an inbox notification.
 * Lets you answer "why didn't the user see this?" later.
 */
@Document(collection = "notification_deliveries")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_user_at_idx",
                def = "{ 'tenantId': 1, 'userId': 1, 'createdAt': -1 }"),
        @CompoundIndex(name = "item_idx",
                def = "{ 'inboxItemId': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDeliveryDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";
    private String userId = "";
    private String inboxItemId = "";

    /** Channel name (`ws`, `email`, `mobile`). */
    private String channel = "";

    /** SENT / SKIPPED / FAILED. */
    private String status = "";

    /** Optional explanation; useful for SKIPPED ("no active WS") and FAILED. */
    private @Nullable String reason;

    private Instant createdAt = Instant.EPOCH;
}
