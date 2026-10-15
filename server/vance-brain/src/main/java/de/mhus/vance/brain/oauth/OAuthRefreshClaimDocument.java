package de.mhus.vance.brain.oauth;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One cluster-wide claim on the refresh of an OAuth token set (see
 * {@link OAuthRefreshClaimService}). The {@code _id} is
 * {@code refresh/<tenant>/<storageProject>/<providerId>}; the insert is the
 * acquire, the holder-checked delete the release.
 *
 * <p>A TTL on {@code claimedAt} reaps claims whose holder died without
 * releasing — long after the takeover, which happens once a claim is older
 * than the caller's own, much shorter TTL.
 */
@Document(collection = "oauth_refresh_claims")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthRefreshClaimDocument {

    @Id
    private String id;

    /** Acquire timestamp; TTL-reaped after an hour. */
    @Indexed(expireAfterSeconds = 3600)
    private Instant claimedAt;

    /** Token of the acquirer — only it can release the claim. */
    private String holder;
}
