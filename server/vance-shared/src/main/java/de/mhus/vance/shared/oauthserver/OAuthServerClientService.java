package de.mhus.vance.shared.oauthserver;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Data owner of {@link OAuthServerClientDocument} — register, look up, and
 * clean up dynamically registered clients.
 *
 * <p>Validation of what a client may register (redirect URI shapes, sizes)
 * is the caller's job; this service stores what it is handed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthServerClientService {

    /** Client ids are opaque; prefixed so a log line shows what kind of id it is. */
    private static final String CLIENT_ID_PREFIX = "dcr_";

    private final OAuthServerClientRepository repository;
    private final MongoTemplate mongoTemplate;

    public OAuthServerClientDocument register(
            String tenantId,
            String clientName,
            @Nullable String clientUri,
            @Nullable String logoUri,
            List<String> redirectUris) {
        Instant now = Instant.now();
        OAuthServerClientDocument doc = OAuthServerClientDocument.builder()
                .clientId(CLIENT_ID_PREFIX + OAuthSecrets.random(18))
                .tenantId(tenantId)
                .clientName(clientName)
                .clientUri(clientUri)
                .logoUri(logoUri)
                .redirectUris(List.copyOf(redirectUris))
                .createdAt(now)
                .lastUsedAt(now)
                .build();
        OAuthServerClientDocument saved = repository.save(doc);
        log.info(
                "OAuth client registered tenant='{}' clientId='{}' name='{}' redirects={}",
                tenantId,
                saved.getClientId(),
                clientName,
                redirectUris);
        return saved;
    }

    /** The registered client, only if it belongs to {@code tenantId}. */
    public Optional<OAuthServerClientDocument> find(String tenantId, String clientId) {
        return repository.findByClientId(clientId).filter(c -> tenantId.equals(c.getTenantId()));
    }

    /** Stamp {@code lastUsedAt} — a single-field update, never a save of a read copy. */
    public void touch(String clientId) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("clientId").is(clientId)),
                new Update().set("lastUsedAt", Instant.now()),
                OAuthServerClientDocument.class);
    }

    /**
     * Delete registrations unused since {@code cutoff} for which
     * {@code stillNeeded} says no — the caller passes "has a live grant",
     * which this service cannot answer itself without reaching into the grant
     * collection.
     *
     * @return number of deleted registrations
     */
    public int deleteUnused(Instant cutoff, Predicate<OAuthServerClientDocument> stillNeeded) {
        int deleted = 0;
        for (OAuthServerClientDocument doc : repository.findByLastUsedAtBefore(cutoff)) {
            if (stillNeeded.test(doc)) {
                continue;
            }
            repository.delete(doc);
            deleted++;
        }
        if (deleted > 0) {
            log.info("OAuth client cleanup: {} unused registration(s) removed", deleted);
        }
        return deleted;
    }
}
