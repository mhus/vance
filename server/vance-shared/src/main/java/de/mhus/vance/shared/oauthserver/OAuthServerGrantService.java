package de.mhus.vance.shared.oauthserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Data owner of {@link OAuthServerGrantDocument} — create, reuse, rotate the
 * refresh token, revoke, and the per-request liveness check of OAuth access
 * tokens.
 *
 * <p>The liveness check mirrors {@code IntegrationTokenService}: unknown grant
 * id = revoked, the identity and pin in the claims are cross-checked against
 * the row, and the decision is cached for
 * {@code vance.oauth-server.liveness-cache-ttl-seconds} — that number is the
 * revocation latency on pods other than the one that revoked.
 *
 * <p>Every write on a grant row touches the fields it means to change via
 * {@code $set}, conditional where a race matters, never a {@code save()} of a
 * copy read earlier: the row is the revocation channel, and a stale write-back
 * would silently clear {@code revokedAt}.
 */
@Service
@Slf4j
public class OAuthServerGrantService {

    /** Grant ids are opaque. */
    private static final int GRANT_ID_BYTES = 16;

    /** Random part of a refresh token. */
    private static final int REFRESH_SECRET_BYTES = 32;

    /** Separator between grant id and secret in a refresh token. */
    private static final char REFRESH_SEPARATOR = '.';

    private final OAuthServerGrantRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Duration cacheTtl;
    private final Clock clock;

    private final ConcurrentHashMap<String, Decision> cache = new ConcurrentHashMap<>();

    private record Decision(boolean active, Instant takenAt) {}

    /** Why a refresh did not produce a new token. */
    public enum RefreshFailure {
        /** Malformed, unknown grant, or not the live token — {@code invalid_grant}. */
        INVALID,
        /** The grant is revoked or past its end. */
        EXPIRED,
        /** The previous token was presented again; the grant is now revoked. */
        REPLAYED
    }

    /** Result of {@link #rotateRefreshToken}: either a grant + new token, or a failure. */
    public record RefreshOutcome(
            @Nullable OAuthServerGrantDocument grant,
            @Nullable String refreshToken,
            @Nullable RefreshFailure failure) {

        static RefreshOutcome ok(OAuthServerGrantDocument grant, String refreshToken) {
            return new RefreshOutcome(grant, refreshToken, null);
        }

        static RefreshOutcome fail(RefreshFailure failure) {
            return new RefreshOutcome(null, null, failure);
        }
    }

    @Autowired
    public OAuthServerGrantService(
            OAuthServerGrantRepository repository,
            MongoTemplate mongoTemplate,
            @Value("${vance.oauth-server.liveness-cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this(repository, mongoTemplate, cacheTtlSeconds, Clock.systemUTC());
    }

    /** Test seam: controllable clock. */
    OAuthServerGrantService(
            OAuthServerGrantRepository repository, MongoTemplate mongoTemplate, long cacheTtlSeconds, Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.cacheTtl = Duration.ofSeconds(Math.max(0, cacheTtlSeconds));
        this.clock = clock;
    }

    private static Query byGrantId(String grantId) {
        return new Query(Criteria.where("grantId").is(grantId));
    }

    /** Create a grant. No refresh token yet — that comes with the first code redemption. */
    public OAuthServerGrantDocument create(
            String tenantId,
            String userId,
            String clientId,
            String clientName,
            @Nullable String resource,
            String scope,
            List<String> scopeProfiles,
            @Nullable String projectId,
            Duration maxLifetime) {
        Instant now = clock.instant();
        OAuthServerGrantDocument doc = OAuthServerGrantDocument.builder()
                .grantId(OAuthSecrets.random(GRANT_ID_BYTES))
                .tenantId(tenantId)
                .userId(userId)
                .clientId(clientId)
                .clientName(clientName)
                .resource(resource)
                .scope(scope)
                .scopeProfiles(List.copyOf(scopeProfiles))
                .projectId(projectId)
                .createdAt(now)
                .absoluteExpiresAt(now.plus(maxLifetime))
                .build();
        OAuthServerGrantDocument saved = repository.save(doc);
        log.info(
                "OAuth grant created tenant='{}' user='{}' client='{}' scope='{}' resource='{}' project='{}'",
                tenantId,
                userId,
                clientId,
                scope,
                resource == null ? "" : resource,
                projectId == null ? "" : projectId);
        return saved;
    }

    /**
     * A live grant the same user already gave the same client for the same
     * resource — reused instead of asking for consent again.
     */
    public Optional<OAuthServerGrantDocument> findReusable(
            String tenantId, String userId, String clientId, @Nullable String resource) {
        Instant now = clock.instant();
        return repository.findByTenantIdAndUserIdAndClientIdAndRevokedAtIsNull(tenantId, userId, clientId).stream()
                .filter(g -> Objects.equals(g.getResource(), resource))
                .filter(g -> g.getAbsoluteExpiresAt() == null
                        || g.getAbsoluteExpiresAt().isAfter(now))
                .findFirst();
    }

    public Optional<OAuthServerGrantDocument> find(String tenantId, String grantId) {
        return repository.findByGrantId(grantId).filter(g -> tenantId.equals(g.getTenantId()));
    }

    /** The owner's grants, newest first. */
    public List<OAuthServerGrantDocument> list(String tenantId, String userId) {
        return repository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId);
    }

    /** Whether any live grant still names this client — keeps its registration alive. */
    public boolean hasLiveGrantForClient(String clientId) {
        return repository.existsByClientIdAndRevokedAtIsNull(clientId);
    }

    /**
     * Start a fresh refresh strand for the grant (after a code redemption):
     * the new token replaces whatever was live, and the replaced one is
     * <em>not</em> remembered as previous — a new strand is not a rotation,
     * and the old client instance presenting its token is not a replay
     * attack, just superseded.
     *
     * @return the new refresh token, or {@code null} when the grant is not live
     */
    public @Nullable String startRefreshStrand(String grantId, Duration slidingTtl) {
        String token = newRefreshToken(grantId);
        Instant now = clock.instant();
        OAuthServerGrantDocument grant = repository.findByGrantId(grantId).orElse(null);
        if (grant == null || grant.getRevokedAt() != null) {
            return null;
        }
        long modified = mongoTemplate
                .updateFirst(
                        byGrantId(grantId)
                                .addCriteria(Criteria.where("revokedAt").is(null)),
                        new Update()
                                .set("refreshTokenHash", OAuthSecrets.hash(token))
                                .set("previousRefreshTokenHash", null)
                                .set("refreshExpiresAt", cap(now.plus(slidingTtl), grant.getAbsoluteExpiresAt()))
                                .set("lastUsedAt", now),
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        return modified > 0 ? token : null;
    }

    /**
     * Rotate a refresh token: the presented token must be the grant's live
     * one; it is replaced by a new one and remembered as previous. Presenting
     * the previous token again revokes the grant. A token presented by a
     * client other than the grant's is refused before anything changes.
     */
    public RefreshOutcome rotateRefreshToken(String tenantId, String presented, String clientId, Duration slidingTtl) {
        String grantId = grantIdOf(presented);
        if (grantId == null) {
            return RefreshOutcome.fail(RefreshFailure.INVALID);
        }
        OAuthServerGrantDocument grant = find(tenantId, grantId).orElse(null);
        if (grant == null || !clientId.equals(grant.getClientId())) {
            return RefreshOutcome.fail(RefreshFailure.INVALID);
        }
        String presentedHash = OAuthSecrets.hash(presented);
        Instant now = clock.instant();
        if (grant.getRevokedAt() != null) {
            return RefreshOutcome.fail(RefreshFailure.EXPIRED);
        }
        if (grant.getPreviousRefreshTokenHash() != null
                && OAuthSecrets.sameHash(presentedHash, grant.getPreviousRefreshTokenHash())) {
            revoke(tenantId, grantId, "refresh-replay");
            log.warn(
                    "OAuth refresh token replay — grant revoked tenant='{}' user='{}' client='{}' grant='{}'",
                    tenantId,
                    grant.getUserId(),
                    grant.getClientId(),
                    grantId);
            return RefreshOutcome.fail(RefreshFailure.REPLAYED);
        }
        if (grant.getRefreshTokenHash() == null || !OAuthSecrets.sameHash(presentedHash, grant.getRefreshTokenHash())) {
            return RefreshOutcome.fail(RefreshFailure.INVALID);
        }
        if (grant.getRefreshExpiresAt() != null && !grant.getRefreshExpiresAt().isAfter(now)) {
            return RefreshOutcome.fail(RefreshFailure.EXPIRED);
        }
        if (grant.getAbsoluteExpiresAt() != null
                && !grant.getAbsoluteExpiresAt().isAfter(now)) {
            return RefreshOutcome.fail(RefreshFailure.EXPIRED);
        }
        String next = newRefreshToken(grantId);
        // Conditional on the presented token still being the live one: two
        // concurrent refreshes with the same token must not both succeed — the
        // loser gets INVALID, which is what a client racing itself deserves.
        long modified = mongoTemplate
                .updateFirst(
                        byGrantId(grantId)
                                .addCriteria(Criteria.where("revokedAt").is(null))
                                .addCriteria(Criteria.where("refreshTokenHash").is(presentedHash)),
                        new Update()
                                .set("refreshTokenHash", OAuthSecrets.hash(next))
                                .set("previousRefreshTokenHash", presentedHash)
                                .set("refreshExpiresAt", cap(now.plus(slidingTtl), grant.getAbsoluteExpiresAt()))
                                .set("lastUsedAt", now),
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        if (modified == 0) {
            return RefreshOutcome.fail(RefreshFailure.INVALID);
        }
        return RefreshOutcome.ok(grant, next);
    }

    /**
     * Whether access tokens of the grant may still authenticate, for the
     * identity and pin the presented claims assert.
     */
    public boolean isActive(String grantId, String tenantId, String userId, @Nullable String projectId) {
        Decision cached = cache.get(grantId);
        if (cached != null && fresh(cached)) {
            return cached.active();
        }
        boolean active = loadAndCheck(grantId, tenantId, userId, projectId);
        cache.put(grantId, new Decision(active, clock.instant()));
        return active;
    }

    private boolean loadAndCheck(String grantId, String tenantId, String userId, @Nullable String projectId) {
        OAuthServerGrantDocument grant = repository.findByGrantId(grantId).orElse(null);
        if (grant == null) {
            log.debug("OAuth token rejected: no grant '{}'", grantId);
            return false;
        }
        if (grant.getRevokedAt() != null) {
            log.debug("OAuth token rejected: grant '{}' revoked at {}", grantId, grant.getRevokedAt());
            return false;
        }
        if (!tenantId.equals(grant.getTenantId()) || !userId.equals(grant.getUserId())) {
            log.debug("OAuth token rejected: identity mismatch on grant '{}'", grantId);
            return false;
        }
        // Same reason as for integration tokens: a project rename cannot follow
        // a signed pin, so a row that moved no longer describes the token.
        if (!Objects.equals(projectId, grant.getProjectId())) {
            log.debug(
                    "OAuth token rejected: pin '{}' no longer matches grant '{}' ('{}')",
                    projectId,
                    grantId,
                    grant.getProjectId());
            return false;
        }
        if (grant.getAbsoluteExpiresAt() != null
                && !grant.getAbsoluteExpiresAt().isAfter(clock.instant())) {
            log.debug("OAuth token rejected: grant '{}' ended at {}", grantId, grant.getAbsoluteExpiresAt());
            return false;
        }
        return true;
    }

    private boolean fresh(Decision decision) {
        return !cacheTtl.isZero()
                && Duration.between(decision.takenAt(), clock.instant()).compareTo(cacheTtl) < 0;
    }

    /**
     * Revoke a grant. Idempotent; keeps the first revocation time and reason.
     *
     * @return true when the grant exists in the tenant
     */
    public boolean revoke(String tenantId, String grantId, String reason) {
        OAuthServerGrantDocument grant = repository.findByGrantId(grantId).orElse(null);
        if (grant == null || !tenantId.equals(grant.getTenantId())) {
            return false;
        }
        long revoked = mongoTemplate
                .updateFirst(
                        byGrantId(grantId)
                                .addCriteria(Criteria.where("revokedAt").is(null)),
                        new Update().set("revokedAt", clock.instant()).set("revokeReason", reason),
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        if (revoked > 0) {
            log.info(
                    "OAuth grant revoked tenant='{}' user='{}' client='{}' grant='{}' reason='{}'",
                    tenantId,
                    grant.getUserId(),
                    grant.getClientId(),
                    grantId,
                    reason);
        }
        cache.remove(grantId);
        return true;
    }

    /**
     * Revoke the grant a refresh token belongs to — RFC 7009 for refresh
     * tokens. Only when the token is the grant's live or previous one; a
     * guessed grant id with a wrong secret revokes nothing.
     */
    public boolean revokeByRefreshToken(String tenantId, String presented) {
        String grantId = grantIdOf(presented);
        if (grantId == null) {
            return false;
        }
        OAuthServerGrantDocument grant = find(tenantId, grantId).orElse(null);
        if (grant == null) {
            return false;
        }
        String hash = OAuthSecrets.hash(presented);
        boolean matches =
                (grant.getRefreshTokenHash() != null && OAuthSecrets.sameHash(hash, grant.getRefreshTokenHash()))
                        || (grant.getPreviousRefreshTokenHash() != null
                                && OAuthSecrets.sameHash(hash, grant.getPreviousRefreshTokenHash()));
        return matches && revoke(tenantId, grantId, "client");
    }

    private String newRefreshToken(String grantId) {
        return grantId + REFRESH_SEPARATOR + OAuthSecrets.random(REFRESH_SECRET_BYTES);
    }

    /** Grant id of a refresh token, or {@code null} when it has no plausible shape. */
    static @Nullable String grantIdOf(String refreshToken) {
        int dot = refreshToken.indexOf(REFRESH_SEPARATOR);
        if (dot <= 0 || dot == refreshToken.length() - 1) {
            return null;
        }
        return refreshToken.substring(0, dot);
    }

    private static Instant cap(Instant candidate, @Nullable Instant ceiling) {
        return ceiling != null && ceiling.isBefore(candidate) ? ceiling : candidate;
    }
}
