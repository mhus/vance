package de.mhus.vance.shared.user;

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
 * The open e-mail code of an account for one {@link EmailLoginPurpose}. A new
 * request replaces it, so there is at most one per account and purpose — a
 * login code and an address confirmation do not disturb each other. Only a
 * keyed hash of the code is kept, never the code itself. Mongo evicts the row
 * at {@link #expiresAt} (TTL index); {@link EmailLoginService} still checks
 * the time, because the eviction runs only once a minute.
 *
 * <p>Written only through {@link EmailLoginCodeService}.
 */
@Document(collection = "email_login_code")
@CompoundIndex(name = "tenant_user_purpose_idx", def = "{'tenantId': 1, 'userId': 1, 'purpose': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLoginCodeDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Name of the account the code belongs to. */
    private String userId = "";

    @Builder.Default
    private EmailLoginPurpose purpose = EmailLoginPurpose.LOGIN;

    /** Random handle the client sends back; the e-mail address is not unique, this is. */
    private String transactionId = "";

    /** HMAC of {@code transactionId + ':' + code}; see {@link EmailLoginService}. */
    private String tokenHash = "";

    /** Where the code was sent: the account's address, or the new one for {@code EMAIL_CHANGE}. */
    private String address = "";

    private Instant requestedAt = Instant.EPOCH;

    /** Mongo evicts the row at this time (TTL index). */
    @Indexed(expireAfter = "0s")
    private Instant expiresAt = Instant.EPOCH;

    /** Verification attempts so far; the code dies at {@link EmailLoginService#MAX_ATTEMPTS}. */
    private int attempts;
}
