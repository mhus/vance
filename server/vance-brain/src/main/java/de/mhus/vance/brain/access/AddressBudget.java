package de.mhus.vance.brain.access;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.oauthserver.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed budget of requests per client address and window, for the open login
 * endpoints. The window starts with the first request of an address and is not
 * extended by later ones.
 */
final class AddressBudget {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final int requestsPerAddress;
    private final Cache<String, AtomicInteger> counts;

    AddressBudget(int requestsPerAddress, Duration window) {
        this.requestsPerAddress = requestsPerAddress;
        this.counts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_ADDRESSES)
                .expireAfterWrite(window)
                .build();
    }

    /** Counts one request and says whether it is still within the budget. */
    boolean allow(HttpServletRequest request) {
        return counts.get(ClientAddresses.of(request), address -> new AtomicInteger())
                        .incrementAndGet()
                <= requestsPerAddress;
    }
}
