package de.mhus.vance.shared.sso;

import de.mhus.vance.shared.oauthserver.OAuthSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * One-time codes that carry a finished SSO sign-in from the system browser
 * into a native app's WebView — see {@link SsoHandoffDocument}.
 */
@Service
@RequiredArgsConstructor
public class SsoHandoffService {

    /** Time between the browser returning to the app and the WebView redeeming. */
    public static final Duration TTL = Duration.ofSeconds(60);

    private final SsoHandoffRepository repository;
    private final MongoTemplate mongoTemplate;

    /** Stores a handoff and returns the code (only its hash is kept). */
    public String create(String tenantId, String username, @Nullable Instant sessionExpiresAt, String appChallenge) {
        String code = OAuthSecrets.random(32);
        repository.save(SsoHandoffDocument.builder()
                .codeHash(OAuthSecrets.hash(code))
                .tenantId(tenantId)
                .username(username)
                .sessionExpiresAt(sessionExpiresAt)
                .appChallenge(appChallenge)
                .expiresAt(Instant.now().plus(TTL))
                .build());
        return code;
    }

    /**
     * Takes the handoff for {@code code} if {@code verifier} answers its
     * challenge. The record is removed in either case once found — a code that
     * met a wrong verifier was seen by someone else and is spent.
     */
    public Optional<SsoHandoffDocument> redeem(String code, String verifier) {
        SsoHandoffDocument taken = mongoTemplate.findAndRemove(
                new Query(Criteria.where("codeHash").is(OAuthSecrets.hash(code))), SsoHandoffDocument.class);
        if (taken == null || taken.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (!OAuthSecrets.sameHash(s256(verifier), taken.getAppChallenge())) {
            return Optional.empty();
        }
        return Optional.of(taken);
    }

    /** PKCE S256: Base64URL(SHA-256(verifier)) without padding. */
    public static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
