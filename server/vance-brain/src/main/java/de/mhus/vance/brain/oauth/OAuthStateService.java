package de.mhus.vance.brain.oauth;

import de.mhus.vance.shared.oauth.OAuthStateDocument;
import de.mhus.vance.shared.oauth.OAuthStateRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Lifecycle of the OAuth Authorization-Code-Grant {@code state}
 * parameter — created on init, validated + consumed on callback.
 * Single-use: a successful {@link #consume} deletes the document.
 *
 * <p>State tokens are 128-bit values from {@link SecureRandom},
 * Base64URL-encoded for URL-safety. {@code expiresAt} defaults to
 * {@link #DEFAULT_TTL} after creation; an explicit overload accepts a
 * caller-chosen duration. The Mongo TTL index on
 * {@code OAuthStateDocument.expiresAt} reaps abandoned flows; the
 * service still rechecks {@code expiresAt} on consume so a window
 * between expiry and TTL eviction can't be exploited.
 *
 * <p>Validation defenses on {@link #consume}:
 * <ul>
 *   <li>state unknown → empty</li>
 *   <li>state expired → empty + document deleted defensively</li>
 *   <li>tenant or user mismatch (CSRF / confused-deputy) → empty +
 *       loud {@code WARN} log + document deleted</li>
 *   <li>any path that returns a value → document is gone afterwards
 *       (single-use guarantee)</li>
 * </ul>
 */
@Service
@Slf4j
public class OAuthStateService {

    /** Default lifetime for a state token if the caller doesn't override. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private static final int STATE_BYTES = 16;

    private final OAuthStateRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthStateService(OAuthStateRepository repository, MongoTemplate mongoTemplate) {
        this(repository, mongoTemplate, Clock.systemUTC());
    }

    OAuthStateService(OAuthStateRepository repository, MongoTemplate mongoTemplate, Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * Mint a fresh state and persist the matching document. Returns the
     * generated {@code state} string — the caller embeds it in the
     * provider's authorize URL.
     */
    public String start(
            String tenantId,
            String userId,
            String providerId,
            @Nullable String returnTo) {
        return start(tenantId, userId, providerId, returnTo, null, DEFAULT_TTL);
    }

    public String start(
            String tenantId,
            String userId,
            String providerId,
            @Nullable String returnTo,
            @Nullable String codeVerifier) {
        return start(tenantId, userId, providerId, returnTo, codeVerifier, DEFAULT_TTL);
    }

    public String start(
            String tenantId,
            String userId,
            String providerId,
            @Nullable String returnTo,
            @Nullable String codeVerifier,
            Duration ttl) {
        Instant now = clock.instant();
        OAuthStateDocument doc = OAuthStateDocument.builder()
                .state(mintState())
                .tenantId(tenantId)
                .userId(userId)
                .providerId(providerId)
                .returnTo(returnTo)
                .codeVerifier(codeVerifier)
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return repository.save(doc).getState();
    }

    /**
     * Verify and consume a state returned by the provider's callback.
     * On success the document is deleted and {@link Consumed} is
     * returned; on any rejection an empty {@link Optional} comes back
     * and the document is also removed when present (defensive cleanup).
     *
     * @param state           the state returned by the provider
     * @param expectedTenant  the tenant of the calling endpoint
     * @param expectedUserId  the authenticated user of the callback
     */
    public Optional<Consumed> consume(
            String state, String expectedTenant, String expectedUserId) {
        if (state == null || state.isBlank()) return Optional.empty();
        // Atomic single-use: find-and-remove in one op so two concurrent
        // callbacks carrying the same state can never both consume it — exactly
        // one wins the delete and gets the document, the loser gets null. (A
        // plain findByState-then-delete left a window where both reads saw the
        // doc before either delete, breaking the single-use guarantee.) All
        // reject paths below act on the already-removed doc; no extra delete.
        OAuthStateDocument doc = mongoTemplate.findAndRemove(
                new Query(Criteria.where("state").is(state)), OAuthStateDocument.class);
        if (doc == null) {
            log.warn("OAuthState.consume: unknown state (no matching document)");
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (doc.getExpiresAt() == null || now.isAfter(doc.getExpiresAt())) {
            log.warn("OAuthState.consume: state expired for tenant='{}' user='{}'",
                    doc.getTenantId(), doc.getUserId());
            return Optional.empty();
        }

        if (!expectedTenant.equals(doc.getTenantId())
                || !expectedUserId.equals(doc.getUserId())) {
            // CSRF / confused-deputy attempt — the caller's auth context
            // disagrees with the initiator's. Hard-reject; the doc is already
            // removed by the atomic find-and-remove above.
            log.warn("OAuthState.consume: tenant/user mismatch — "
                    + "stored=({}/{}) called-by=({}/{}) — dropping state",
                    doc.getTenantId(), doc.getUserId(),
                    expectedTenant, expectedUserId);
            return Optional.empty();
        }

        return Optional.of(new Consumed(
                doc.getProviderId(), doc.getReturnTo(), doc.getCodeVerifier()));
    }

    private String mintState() {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returned by {@link #consume} on a successful match — the fields
     * the callback handler needs to continue the flow. {@code codeVerifier}
     * is the PKCE verifier paired with the state, replayed at token
     * exchange; {@code null} when the flow wasn't PKCE-enabled.
     */
    public record Consumed(
            String providerId,
            @Nullable String returnTo,
            @Nullable String codeVerifier) {
    }
}
