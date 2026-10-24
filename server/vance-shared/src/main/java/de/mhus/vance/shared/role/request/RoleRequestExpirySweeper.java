package de.mhus.vance.shared.role.request;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lapses role requests nobody decided within {@link RoleRequestService#DEFAULT_TTL}.
 *
 * <p>Without this, an undecided request would stay approvable forever — and an
 * approval is most dangerous long after the situation that prompted it has been
 * forgotten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleRequestExpirySweeper {

    private final RoleRequestService requests;

    /** Hourly is ample for a seven-day deadline. */
    @Scheduled(fixedDelayString = "#{${vance.permission.request-expiry-interval-ms:3600000}}")
    public void sweep() {
        try {
            requests.expireStale(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Role-request expiry sweep failed: {}", e.toString());
        }
    }
}
