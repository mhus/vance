package de.mhus.vance.brain.lease;

import de.mhus.vance.shared.lease.LeaseHandler;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * All {@link LeaseHandler} beans of this application, by type. The reaper
 * dispatches revocations through this registry; the shared
 * {@code ResourceLeaseService} independently rejects acquisitions with a
 * type no handler could clean up.
 */
@Component
@Slf4j
public class LeaseHandlerRegistry {

    private final Map<String, LeaseHandler> byType;

    public LeaseHandlerRegistry(List<LeaseHandler> handlers) {
        Map<String, LeaseHandler> map = new HashMap<>();
        for (LeaseHandler handler : handlers) {
            LeaseHandler existing = map.put(handler.type(), handler);
            if (existing != null) {
                throw new IllegalStateException("Two LeaseHandler beans for type '" + handler.type() + "': "
                        + existing.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
        this.byType = Map.copyOf(map);
    }

    /** The handler for a lease type, if this application has one. */
    public Optional<LeaseHandler> byType(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** Dispatches a revocation on this pod; handler failures are contained here. */
    public void dispatch(LeaseHolderInfo info) {
        LeaseHandler handler = byType.get(info.type());
        if (handler == null) {
            log.warn(
                    "lease revoke: no handler for type '{}' (holder {} on '{}') — resource left to its own expiry",
                    info.type(),
                    info.token(),
                    info.holderRef());
            return;
        }
        try {
            handler.onRevoked(info);
        } catch (RuntimeException e) {
            log.warn("lease revoke: handler '{}' failed for holder {}: {}", info.type(), info.token(), e.toString());
        }
    }
}
