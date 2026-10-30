package de.mhus.vance.shared.user;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The one open e-mail code of an account — embedded in
 * {@link UserDocument#getEmailLogin()}. A new request replaces it. Only a keyed
 * hash of the code is kept, never the code itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLoginTransaction {

    /** Random handle the client sends back; the e-mail address is not unique, this is. */
    private String transactionId = "";

    /** HMAC of {@code transactionId + ':' + code}; see {@link EmailLoginService}. */
    private String tokenHash = "";

    @Builder.Default
    private EmailLoginPurpose purpose = EmailLoginPurpose.LOGIN;

    /** Where the code was sent: the account's address, or the new one for {@code EMAIL_CHANGE}. */
    private String address = "";

    private Instant requestedAt = Instant.EPOCH;

    private Instant expiresAt = Instant.EPOCH;

    /** Verification attempts so far; the code dies at {@link EmailLoginService#MAX_ATTEMPTS}. */
    private int attempts;
}
