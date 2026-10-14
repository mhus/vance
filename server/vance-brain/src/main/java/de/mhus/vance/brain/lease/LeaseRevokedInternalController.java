package de.mhus.vance.brain.lease;

import de.mhus.vance.shared.lease.LeaseHolderInfo;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pod-to-pod revoke ingest. Another pod's {@link ResourceLeaseSweeper}
 * reaped a lease whose holder lives <b>here</b> — we dispatch it to the
 * local handler. Authentication: shared {@code X-Vance-Internal-Token}
 * (enforced by {@code InternalAccessFilter} on the {@code /internal/}
 * prefix); no user-identity surface, mirroring
 * {@code DocumentChangedInternalController}.
 */
@RestController
@RequestMapping("/internal/lease")
@RequiredArgsConstructor
@Slf4j
public class LeaseRevokedInternalController {

    private final LeaseHandlerRegistry registry;

    @PostMapping("/revoked")
    public ResponseEntity<Void> revoked(@RequestBody @NotNull LeaseHolderInfo info) {
        if (info == null || info.type() == null) {
            log.warn("LeaseRevokedInternalController: rejecting malformed revoke payload");
            return ResponseEntity.badRequest().build();
        }
        registry.dispatch(info);
        return ResponseEntity.ok().build();
    }
}
