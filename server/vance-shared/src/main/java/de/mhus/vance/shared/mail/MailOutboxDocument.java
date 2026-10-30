package de.mhus.vance.shared.mail;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One mail waiting for delivery. The brain writes entries; a sender (the
 * enterprise Anus) claims and delivers them — see {@link MailOutboxService}.
 *
 * <p>The body is stored encrypted because it can carry a one-time login
 * code, and is dropped as soon as the entry leaves the delivery path
 * ({@code SENT} / {@code FAILED}). A TTL index on {@link #expiresAt} evicts
 * the entry itself.
 */
@Document(collection = "mail_outbox")
@CompoundIndex(name = "status_next_attempt_idx", def = "{'status': 1, 'nextAttemptAt': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailOutboxDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String to = "";

    private String subject = "";

    /** AES-GCM blob of the mail body; {@code null} once the entry is done. */
    private @Nullable String bodyEncrypted;

    @Builder.Default
    private MailOutboxStatus status = MailOutboxStatus.PENDING;

    /** Delivery attempts started so far (incremented on every claim). */
    private int attempts;

    /** Earliest time the entry may be claimed while {@code PENDING}. */
    private Instant nextAttemptAt = Instant.EPOCH;

    /** While {@code SENDING}: when another sender may take the entry over. */
    private @Nullable Instant leaseUntil;

    private @Nullable String lastError;

    private Instant createdAt = Instant.EPOCH;

    private @Nullable Instant sentAt;

    /** Mongo evicts the entry at this time (TTL index). */
    @Indexed(expireAfter = "0s")
    private Instant expiresAt = Instant.EPOCH;
}
