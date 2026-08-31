package de.mhus.vance.shared.integration;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Data owner of {@link IntegrationTokenDocument} — mint, list, revoke, and the
 * per-request liveness check that makes a long-lived token revocable.
 *
 * <p><b>Fail-closed on an unknown token id.</b> A {@code jti} with no row is
 * rejected exactly like a revoked one. That covers the two ways a row can be
 * missing — a mint that died between signing and writing, and a row deleted by
 * hand — and both should stop the token rather than let it through.
 *
 * <p><b>The liveness check is cached, and the cache TTL is the revocation
 * latency.</b> This is the whole design trade and it is worth being explicit
 * about: without a cache every request costs a Mongo read; with an unbounded
 * cache a revoked token keeps working. A short window (default 30 s) makes the
 * normal case a map hit while bounding the damage of a revocation to seconds —
 * and, crucially, that bound is a number the operator sets, not one baked into
 * a token that was issued a year ago. Revoking on <em>this</em> pod evicts
 * immediately; other pods notice within the window. No cross-pod invalidation
 * channel is built for that, because a channel that can fail silently would
 * make the bound less trustworthy, not more.
 *
 * <p>{@link #lastUsedAt} is written at most once per cache window, from the
 * same miss that reloads the row. A write per request would be a database write
 * on every call of an endpoint whose whole point is being cheap; a write per
 * window still answers the only question this field exists for — "is anything
 * still using this token".
 */
@Service
@Slf4j
public class IntegrationTokenService {

    /** Token ids are opaque; 128 bits is plenty and keeps the claim short. */
    private static final int TOKEN_ID_BYTES = 16;

    private final IntegrationTokenRepository repository;
    private final Duration cacheTtl;
    private final SecureRandom random = new SecureRandom();

    /**
     * {@code tokenId -> } the decision and when it was taken. Bounded in
     * practice by the number of live tokens, which is a human-scale number:
     * one per integration a person set up.
     */
    private final ConcurrentHashMap<String, Decision> cache = new ConcurrentHashMap<>();

    private record Decision(boolean active, Instant takenAt) {}

    public IntegrationTokenService(
            IntegrationTokenRepository repository,
            @Value("${vance.integration-token.cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this.repository = repository;
        this.cacheTtl = Duration.ofSeconds(Math.max(0, cacheTtlSeconds));
    }

    /**
     * Mint a registry row and return it. The caller signs a token carrying the
     * returned {@link IntegrationTokenDocument#getTokenId()} as {@code jti}.
     *
     * <p>The row is written <em>before</em> the token is signed so the failure
     * mode is a row without a token (harmless — it authenticates nothing and
     * shows up in the owner's list to be revoked) rather than a token without a
     * row (which would be unrevocable if absence meant "allow" — it does not,
     * but relying on that ordering as well costs nothing).
     */
    public IntegrationTokenDocument create(
            String tenantId, String userId, String scopeProfile,
            @Nullable String projectId, String label,
            @Nullable String createdBy, @Nullable Instant expiresAt) {
        IntegrationTokenDocument doc = IntegrationTokenDocument.builder()
                .tokenId(newTokenId())
                .tenantId(tenantId)
                .userId(userId)
                .scopeProfile(scopeProfile)
                .projectId(projectId)
                .label(label)
                .createdAt(Instant.now())
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .build();
        IntegrationTokenDocument saved = repository.save(doc);
        log.info("IntegrationTokenService.create tenant='{}' user='{}' profile='{}' "
                        + "project='{}' label='{}' expiresAt={}",
                tenantId, userId, scopeProfile, projectId == null ? "" : projectId,
                label, expiresAt);
        return saved;
    }

    /**
     * Whether the token behind {@code tokenId} may still authenticate a
     * request, for the identity the presented claims assert.
     *
     * <p>The identity cross-check is not redundant with the signature: the
     * signature proves the claims were minted by us, this proves they were
     * minted as <em>this</em> row. Without it a token whose row was later
     * re-created for another user would keep the old claims alive.
     */
    public boolean isActive(String tokenId, String tenantId, String userId) {
        Decision cached = cache.get(tokenId);
        if (cached != null && fresh(cached)) {
            return cached.active();
        }
        boolean active = loadAndCheck(tokenId, tenantId, userId);
        cache.put(tokenId, new Decision(active, Instant.now()));
        return active;
    }

    private boolean loadAndCheck(String tokenId, String tenantId, String userId) {
        IntegrationTokenDocument doc = repository.findByTokenId(tokenId).orElse(null);
        if (doc == null) {
            log.debug("Integration token '{}' rejected: no registry row", tokenId);
            return false;
        }
        if (doc.getRevokedAt() != null) {
            log.debug("Integration token '{}' rejected: revoked at {}", tokenId, doc.getRevokedAt());
            return false;
        }
        if (!tenantId.equals(doc.getTenantId()) || !userId.equals(doc.getUserId())) {
            log.debug("Integration token '{}' rejected: identity mismatch — "
                            + "claims {}/{} row {}/{}",
                    tokenId, tenantId, userId, doc.getTenantId(), doc.getUserId());
            return false;
        }
        // The signature check already refuses an expired token; this is the row
        // saying the same thing, and it is the one that still answers after a
        // token was minted without an expiry at all.
        if (doc.getExpiresAt() != null && doc.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Integration token '{}' rejected: expired at {}", tokenId, doc.getExpiresAt());
            return false;
        }
        doc.setLastUsedAt(Instant.now());
        repository.save(doc);
        return true;
    }

    private boolean fresh(Decision decision) {
        return !cacheTtl.isZero()
                && Duration.between(decision.takenAt(), Instant.now()).compareTo(cacheTtl) < 0;
    }

    /** The owner's own tokens, newest first. Never carries a token value. */
    public List<IntegrationTokenDocument> list(String tenantId, String userId) {
        return repository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId);
    }

    public Optional<IntegrationTokenDocument> find(String tenantId, String tokenId) {
        return repository.findByTokenId(tokenId)
                .filter(d -> tenantId.equals(d.getTenantId()));
    }

    /**
     * Revoke a token. Idempotent — revoking an already-revoked token keeps the
     * original timestamp, because when it stopped working is the interesting
     * fact and a second call should not rewrite it.
     *
     * @return true when a row was found (whether or not it was already revoked)
     */
    public boolean revoke(String tenantId, String tokenId) {
        IntegrationTokenDocument doc = repository.findByTokenId(tokenId).orElse(null);
        if (doc == null || !tenantId.equals(doc.getTenantId())) {
            return false;
        }
        if (doc.getRevokedAt() == null) {
            doc.setRevokedAt(Instant.now());
            repository.save(doc);
            log.info("IntegrationTokenService.revoke tenant='{}' user='{}' tokenId='{}'",
                    tenantId, doc.getUserId(), tokenId);
        }
        // Local eviction so a revocation is immediate on the pod that took it.
        cache.remove(tokenId);
        return true;
    }

    private String newTokenId() {
        byte[] bytes = new byte[TOKEN_ID_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
