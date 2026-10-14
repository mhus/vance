package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes dynamically registered clients nobody uses: no authorization
 * request for {@link #UNUSED_AFTER} and no live grant. The registration
 * endpoint is open, so without this the collection only ever grows.
 *
 * <p>Idempotent and safe on every pod at once — deleting an already deleted
 * registration is a no-op.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuthClientCleanup {

    static final Duration UNUSED_AFTER = Duration.ofDays(30);

    private final OAuthServerClientService clientService;
    private final OAuthServerGrantService grantService;

    @Scheduled(initialDelay = 600_000L, fixedDelayString = "${vance.oauth-server.client-cleanup-interval-ms:21600000}")
    public void sweep() {
        try {
            clientService.deleteUnused(
                    Instant.now().minus(UNUSED_AFTER),
                    client -> grantService.hasLiveGrantForClient(client.getClientId()));
        } catch (RuntimeException e) {
            log.warn("OAuth client cleanup failed: {}", e.toString());
        }
    }
}
