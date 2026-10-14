package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
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
import org.springframework.context.ApplicationEventPublisher;
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
 * the row, the account must still be allowed to log in, and the decision is
 * cached for {@code vance.oauth-server.liveness-cache-ttl-seconds} — that
 * number is the revocation latency on pods other than the one that revoked.
 * The cache is keyed on the full claim set, not on the grant id alone: after
 * a user rename the same grant answers differently for the old and the new
 * name.
 *
 * <p>Every effective revocation goes through one place: it stamps the row,
 * drops the cached decisions, writes the audit record and publishes
 * {@link OAuthGrantRevokedEvent}.
 *
 * <p>Every write on a grant row touches the fields it means to change via
 * {@code $set}, conditional where a race matters, never a {@code save()} of a
 * copy read earlier: the row is the revocation channel, and a stale write-back
 * would silently clear {@code revokedAt}.
 */
@Service
@Slf4j
public class OAuthServerGrantService {

    /** How many replaced refresh tokens a grant remembers for replay detection. */
    public static final int REPLAY_HISTORY = 20;

    /** Grant ids are opaque. */
    private static final int GRANT_ID_BYTES = 16;

    /** Random part of a refresh token. */
    private static final int REFRESH_SECRET_BYTES = 32;

    /** Separator between grant id and secret in a refresh token. */
    private static final char REFRESH_SEPARATOR = '.';

    private final OAuthServerGrantRepository repository;
    private final MongoTemplate mongoTemplate;
    private final UserService userService;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final Duration cacheTtl;
    private final Clock clock;

    private final ConcurrentHashMap<CacheKey, Decision> cache = new ConcurrentHashMap<>();

    private record CacheKey(
            String grantId,
            String tenantId,
            String userId,
            @Nullable String projectId) {}

    private record Decision(boolean active, Instant takenAt) {}

    /** Why a refresh did not produce a new token. */
    public enum RefreshFailure {
        /** Malformed, unknown grant, or not the live token — {@code invalid_grant}. */
        INVALID,
        /** The grant is revoked or past its end. */
        EXPIRED,
        /** A replaced token was presented again; the grant is now revoked. */
        REPLAYED
    }

    /**
     * Result of {@link #inspectRefreshToken} and {@link #rotateRefreshToken}:
     * either the grant (plus the new token after a rotation), or a failure.
     */
    public record RefreshOutcome(
            @Nullable OAuthServerGrantDocument grant,
            @Nullable String refreshToken,
            @Nullable RefreshFailure failure) {

        static RefreshOutcome checked(OAuthServerGrantDocument grant) {
            return new RefreshOutcome(grant, null, null);
        }

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
            UserService userService,
            ApplicationEventPublisher events,
            AuditService auditService,
            @Value("${vance.oauth-server.liveness-cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this(repository, mongoTemplate, userService, events, auditService, cacheTtlSeconds, Clock.systemUTC());
    }

    /** Test seam: controllable clock. */
    OAuthServerGrantService(
            OAuthServerGrantRepository repository,
            MongoTemplate mongoTemplate,
            UserService userService,
            ApplicationEventPublisher events,
            AuditService auditService,
            long cacheTtlSeconds,
            Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.userService = userService;
        this.events = events;
        this.auditService = auditService;
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
     * resource — reused instead of asking for consent again. Only asked for
     * third-party clients; a first-party login always gets a grant of its own.
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

    /**
     * Whether any live grant — not revoked and not past its absolute end —
     * still names this client. Keeps its registration alive.
     */
    public boolean hasLiveGrantForClient(String clientId) {
        Query query = new Query(
                        Criteria.where("clientId").is(clientId).and("revokedAt").is(null))
                .addCriteria(new Criteria()
                        .orOperator(
                                Criteria.where("absoluteExpiresAt").is(null),
                                Criteria.where("absoluteExpiresAt").gt(clock.instant())));
        return mongoTemplate.exists(query, OAuthServerGrantDocument.class);
    }

    /**
     * Start a fresh refresh strand for the grant (after a code redemption):
     * the new token replaces whatever was live, and the replaced ones are
     * <em>not</em> remembered — a new strand is not a rotation, and the old
     * client instance presenting its token is not a replay attack, just
     * superseded.
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
                                .set("previousRefreshTokenHashes", List.of())
                                .set("previousRefreshTokenHash", null)
                                .set("refreshExpiresAt", cap(now.plus(slidingTtl), grant.getAbsoluteExpiresAt()))
                                .set("lastUsedAt", now),
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        return modified > 0 ? token : null;
    }

    /**
     * Check a presented refresh token <em>without</em> rotating it: the
     * caller can then verify what it needs (resource, account) before the
     * token is spent. The token must be the grant's live one and belong to
     * {@code clientId}. A token the grant already replaced is a replay — that
     * revokes the grant here, because the presenter demonstrably holds a copy
     * of a used credential of this client.
     */
    public RefreshOutcome inspectRefreshToken(String tenantId, String presented, String clientId) {
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
        if (isReplacedToken(grant, presentedHash)) {
            revokeAfterReplay(tenantId, grantId, "refresh");
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
        return RefreshOutcome.checked(grant);
    }

    /**
     * Rotate a refresh token: the same checks as {@link #inspectRefreshToken},
     * then the presented token is replaced by a new one and appended to the
     * grant's replay history (capped at {@link #REPLAY_HISTORY}).
     */
    public RefreshOutcome rotateRefreshToken(String tenantId, String presented, String clientId, Duration slidingTtl) {
        RefreshOutcome checked = inspectRefreshToken(tenantId, presented, clientId);
        OAuthServerGrantDocument grant = checked.grant();
        if (checked.failure() != null || grant == null) {
            return checked;
        }
        String presentedHash = OAuthSecrets.hash(presented);
        Instant now = clock.instant();
        String next = newRefreshToken(grant.getGrantId());
        Update update = new Update()
                .set("refreshTokenHash", OAuthSecrets.hash(next))
                .set("refreshExpiresAt", cap(now.plus(slidingTtl), grant.getAbsoluteExpiresAt()))
                .set("lastUsedAt", now);
        // A row written by an earlier version still carries its single previous
        // hash — fold it into the history so it keeps counting as a replay.
        String legacy = grant.getPreviousRefreshTokenHash();
        if (legacy != null) {
            update.set("previousRefreshTokenHash", null);
            update.push("previousRefreshTokenHashes").slice(-REPLAY_HISTORY).each(legacy, presentedHash);
        } else {
            update.push("previousRefreshTokenHashes").slice(-REPLAY_HISTORY).each(presentedHash);
        }
        // Conditional on the presented token still being the live one: two
        // concurrent refreshes with the same token must not both succeed — the
        // loser gets INVALID, which is what a client racing itself deserves.
        long modified = mongoTemplate
                .updateFirst(
                        byGrantId(grant.getGrantId())
                                .addCriteria(Criteria.where("revokedAt").is(null))
                                .addCriteria(Criteria.where("refreshTokenHash").is(presentedHash)),
                        update,
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        if (modified == 0) {
            return RefreshOutcome.fail(RefreshFailure.INVALID);
        }
        return RefreshOutcome.ok(grant, next);
    }

    private static boolean isReplacedToken(OAuthServerGrantDocument grant, String presentedHash) {
        String legacy = grant.getPreviousRefreshTokenHash();
        if (legacy != null && OAuthSecrets.sameHash(presentedHash, legacy)) {
            return true;
        }
        List<String> history = grant.getPreviousRefreshTokenHashes();
        return history != null && history.stream().anyMatch(h -> OAuthSecrets.sameHash(presentedHash, h));
    }

    /**
     * Whether access tokens of the grant may still authenticate, for the
     * identity and pin the presented claims assert.
     */
    public boolean isActive(String grantId, String tenantId, String userId, @Nullable String projectId) {
        CacheKey key = new CacheKey(grantId, tenantId, userId, projectId);
        Decision cached = cache.get(key);
        if (cached != null && fresh(cached)) {
            return cached.active();
        }
        boolean active = loadAndCheck(grantId, tenantId, userId, projectId);
        cache.put(key, new Decision(active, clock.instant()));
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
        // The grant acts as its account: a disabled account stops its access
        // tokens within the cache window, not only at the next refresh.
        if (!accountMayAct(tenantId, userId)) {
            log.debug("OAuth token rejected: account '{}' of grant '{}' cannot log in", userId, grantId);
            return false;
        }
        return true;
    }

    private boolean accountMayAct(String tenantId, String userId) {
        UserDocument user = userService.findByTenantAndName(tenantId, userId).orElse(null);
        return user != null && user.getStatus() == UserStatus.ACTIVE && user.isLoginEnabled();
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
        OAuthServerGrantDocument grant = find(tenantId, grantId).orElse(null);
        if (grant == null) {
            return false;
        }
        revokeRow(grant, reason);
        return true;
    }

    /**
     * A used code or a replaced refresh token was presented again by someone
     * who passed the client binding: a copy of the credential exists. Records
     * the replay and revokes the grant ({@code code-replay} /
     * {@code refresh-replay}).
     *
     * @param tokenKind {@code code} or {@code refresh}
     */
    public void revokeAfterReplay(String tenantId, String grantId, String tokenKind) {
        OAuthServerGrantDocument grant = find(tenantId, grantId).orElse(null);
        if (grant == null) {
            return;
        }
        log.warn(
                "OAuth {} replay — revoking grant tenant='{}' user='{}' client='{}' grant='{}'",
                tokenKind,
                tenantId,
                grant.getUserId(),
                grant.getClientId(),
                grantId);
        auditService.authOAuthTokenReplay(tenantId, grant.getUserId(), grantId, grant.getClientId(), tokenKind);
        revokeRow(grant, tokenKind + "-replay");
    }

    /**
     * Revoke the grant a refresh token belongs to — RFC 7009 for refresh
     * tokens. Only when the token is the grant's live or a replaced one, and
     * — when the caller names a client — only for a token issued to that
     * client (RFC 7009 §2.1). A guessed grant id with a wrong secret revokes
     * nothing.
     *
     * @return true when a grant matched (whether or not it was already revoked)
     */
    public boolean revokeByRefreshToken(String tenantId, String presented, @Nullable String clientId) {
        String grantId = grantIdOf(presented);
        if (grantId == null) {
            return false;
        }
        OAuthServerGrantDocument grant = find(tenantId, grantId).orElse(null);
        if (grant == null) {
            return false;
        }
        if (clientId != null && !clientId.isBlank() && !clientId.equals(grant.getClientId())) {
            log.trace("OAuth revoke ignored: refresh token of grant '{}' presented by another client", grantId);
            return false;
        }
        String hash = OAuthSecrets.hash(presented);
        boolean matches =
                (grant.getRefreshTokenHash() != null && OAuthSecrets.sameHash(hash, grant.getRefreshTokenHash()))
                        || isReplacedToken(grant, hash);
        if (!matches) {
            return false;
        }
        revokeRow(grant, "client");
        return true;
    }

    /**
     * Revoke every live grant of an account — on a password change, the
     * user's way of ending whatever the old password handed out.
     *
     * @return number of grants this call revoked
     */
    public int revokeAllForUser(String tenantId, String userId, String reason) {
        int revoked = 0;
        for (OAuthServerGrantDocument grant : repository.findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId)) {
            if (revokeRow(grant, reason)) {
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info(
                    "OAuth grants revoked tenant='{}' user='{}' count={} reason='{}'",
                    tenantId,
                    userId,
                    revoked,
                    reason);
        }
        return revoked;
    }

    /**
     * Revoke every live grant pinned to a project — it is being renamed (the
     * signed pin cannot follow) or deleted.
     *
     * @return number of grants this call revoked
     */
    public int revokeAllForProject(String tenantId, String projectId, String reason) {
        int revoked = 0;
        for (OAuthServerGrantDocument grant :
                repository.findByTenantIdAndProjectIdAndRevokedAtIsNull(tenantId, projectId)) {
            if (revokeRow(grant, reason)) {
                revoked++;
            }
        }
        return revoked;
    }

    /**
     * The one place a grant goes from live to revoked: conditional stamp,
     * cache eviction, audit, event.
     *
     * @return true when this call revoked it, false when it already was
     */
    private boolean revokeRow(OAuthServerGrantDocument grant, String reason) {
        String grantId = grant.getGrantId();
        long revoked = mongoTemplate
                .updateFirst(
                        byGrantId(grantId)
                                .addCriteria(Criteria.where("revokedAt").is(null)),
                        new Update().set("revokedAt", clock.instant()).set("revokeReason", reason),
                        OAuthServerGrantDocument.class)
                .getModifiedCount();
        cache.keySet().removeIf(k -> k.grantId().equals(grantId));
        if (revoked == 0) {
            return false;
        }
        log.info(
                "OAuth grant revoked tenant='{}' user='{}' client='{}' grant='{}' reason='{}'",
                grant.getTenantId(),
                grant.getUserId(),
                grant.getClientId(),
                grantId,
                reason);
        auditService.authOAuthGrantRevoked(grant.getTenantId(), grant.getUserId(), grantId, reason);
        try {
            events.publishEvent(new OAuthGrantRevokedEvent(grant.getTenantId(), grantId, grant.getUserId(), reason));
        } catch (RuntimeException e) {
            // The row is revoked — that is the authoritative part. A failing
            // listener must not abort a bulk revocation half-way or turn the
            // revoke into an error; its follow-up falls back to the liveness check.
            log.warn("OAuthGrantRevokedEvent listener failed for grant '{}': {}", grantId, e.toString());
        }
        return true;
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
