package de.mhus.vance.shared.sso;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Pending SSO sign-ins. A request is taken atomically — the callback that
 * removes it is the only one that can use it, a replayed callback finds
 * nothing.
 */
@Service
@RequiredArgsConstructor
public class SsoLoginRequestService {

    /** How long a user may spend at the identity provider. */
    public static final Duration TTL = Duration.ofMinutes(10);

    private final SsoLoginRequestRepository repository;
    private final MongoTemplate mongoTemplate;

    public SsoLoginRequestDocument create(SsoLoginRequestDocument request) {
        Instant now = Instant.now();
        request.setId(null);
        request.setCreatedAt(now);
        request.setExpiresAt(now.plus(TTL));
        return repository.save(request);
    }

    /**
     * Removes and returns the request for {@code stateHash}, if it is there
     * and not expired. The TTL index evicts lazily, so expiry is checked here
     * as well.
     */
    public Optional<SsoLoginRequestDocument> take(String stateHash) {
        Query query = new Query(Criteria.where("stateHash").is(stateHash));
        SsoLoginRequestDocument taken = mongoTemplate.findAndRemove(query, SsoLoginRequestDocument.class);
        if (taken == null || taken.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(taken);
    }
}
