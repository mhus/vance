package de.mhus.vance.simpleauth;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lapses permission requests nobody decided within
 * {@link PermissionRequestService#DEFAULT_TTL}.
 *
 * <p>Without this, an undecided request would stay approvable forever —
 * and an approval is most dangerous long after the situation that
 * prompted it has been forgotten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionRequestExpirySweeper {

    private final PermissionRequestService requests;

    /** Hourly is ample for a seven-day deadline. */
    @Scheduled(fixedDelayString = "#{${vance.permission.request-expiry-interval-ms:3600000}}")
    public void sweep() {
        try {
            requests.expireStale(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Permission-request expiry sweep failed: {}", e.toString());
        }
    }
}
