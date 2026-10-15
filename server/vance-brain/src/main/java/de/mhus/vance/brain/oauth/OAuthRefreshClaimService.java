package de.mhus.vance.brain.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Cluster-wide mutual exclusion for OAuth token refreshes. A shared project
 * or tenant login is used from every pod, and authorization servers that
 * rotate refresh tokens treat a second use of the old one as replay — they
 * revoke the whole grant. The per-pod monitor of
 * {@link OAuthTokenRefresher} cannot see the other pods, so the refresh
 * additionally holds one {@link OAuthRefreshClaimDocument} in Mongo. Redis is
 * deliberately not used: it is optional and reserved for live features.
 *
 * <p>A claim of a holder that died is taken over once it is older than the
 * TTL the caller names; the release carries the acquirer's token, so a late
 * holder cannot free its successor's claim.
 *
 * <p>Owner of {@code oauth_refresh_claims}; nothing else writes there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthRefreshClaimService {

    private final MongoTemplate mongoTemplate;

    /** Claim key of one owner's token set of one provider. */
    public static String key(String tenantId, OAuthTokenOwner owner, String providerId) {
        return "refresh/" + tenantId + "/" + owner.storageProjectId() + "/" + providerId;
    }

    /**
     * Takes the claim {@code key}. Succeeds when nobody holds it, or when the
     * holder's claim is older than {@code ttl}.
     *
     * @return the holder token for {@link #release}, or {@code null} while
     *         another holder's claim is fresh
     */
    public @Nullable String tryClaim(String key, Duration ttl) {
        Instant now = Instant.now();
        String holder = UUID.randomUUID().toString();
        try {
            mongoTemplate.insert(OAuthRefreshClaimDocument.builder()
                    .id(key)
                    .claimedAt(now)
                    .holder(holder)
                    .build());
            return holder;
        } catch (DuplicateKeyException e) {
            Query stale =
                    new Query(Criteria.where("_id").is(key).and("claimedAt").lt(now.minus(ttl)));
            boolean taken = mongoTemplate
                            .updateFirst(
                                    stale,
                                    new Update().set("claimedAt", now).set("holder", holder),
                                    OAuthRefreshClaimDocument.class)
                            .getModifiedCount()
                    == 1;
            if (taken) {
                log.info("OAuth refresh claim '{}' was stale — taken over", key);
            }
            return taken ? holder : null;
        }
    }

    /** Gives the claim back — only while {@code holder} still owns it. */
    public void release(String key, String holder) {
        Query owned = new Query(Criteria.where("_id").is(key).and("holder").is(holder));
        if (mongoTemplate.remove(owned, OAuthRefreshClaimDocument.class).getDeletedCount() == 0) {
            log.info("OAuth refresh claim '{}' no longer held by this acquirer — release ignored", key);
        }
    }
}
