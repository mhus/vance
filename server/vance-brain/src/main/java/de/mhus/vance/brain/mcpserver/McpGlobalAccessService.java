package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads and caches {@code _vance/config/mcp-global-access.yaml} from the
 * tenant's {@code _tenant} project — the gate for narrowed credentials at the
 * global MCP door.
 *
 * <p>Only the {@code _tenant} project is read: the global door is tenant-wide,
 * so its rule has exactly one home, writable by tenant admins only
 * ({@code _vance/} needs ADMIN, rule R4). Cached per tenant, invalidated by the
 * change event (a {@code _tenant} change is broadcast to every pod), with a
 * TTL as a second bound. Fail-closed like {@link McpProjectAccessService}.
 */
@Service
@Slf4j
public class McpGlobalAccessService {

    public static final String CONFIG_PATH = "_vance/config/mcp-global-access.yaml";

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DocumentService documentService;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(McpGlobalAccessConfig config, Instant expiresAt) {}

    @Autowired
    public McpGlobalAccessService(DocumentService documentService) {
        this(documentService, Clock.systemUTC());
    }

    McpGlobalAccessService(DocumentService documentService, Clock clock) {
        this.documentService = documentService;
        this.clock = clock;
    }

    /** The entry admitting {@code caller} at the tenant's global door, or {@code null} = deny. */
    public McpGlobalAccessConfig.@Nullable Entry resolveEntry(String tenantId, McpCaller caller) {
        return load(tenantId).resolve(caller);
    }

    private McpGlobalAccessConfig load(String tenantId) {
        Instant now = clock.instant();
        Cached cached = cache.get(tenantId);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.config();
        McpGlobalAccessConfig parsed = readAndParse(tenantId);
        cache.put(tenantId, new Cached(parsed, now.plus(CACHE_TTL)));
        return parsed;
    }

    private McpGlobalAccessConfig readAndParse(String tenantId) {
        Optional<LookupResult> hit;
        try {
            hit = documentService.lookupCascade(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CONFIG_PATH);
        } catch (RuntimeException ex) {
            log.warn("mcp-global-access: reading config failed for tenant '{}' — denying: {}", tenantId, ex.toString());
            return McpGlobalAccessConfig.EMPTY;
        }
        if (hit.isEmpty()) return McpGlobalAccessConfig.EMPTY;
        try {
            return McpGlobalAccessConfig.parse(hit.get().content());
        } catch (RuntimeException ex) {
            log.warn("mcp-global-access: malformed config in tenant '{}' — denying: {}", tenantId, ex.getMessage());
            return McpGlobalAccessConfig.EMPTY;
        }
    }

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (CONFIG_PATH.equals(event.path()) && HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
            cache.remove(event.tenantId());
            log.debug("mcp-global-access: config changed, cache evicted for tenant '{}'", event.tenantId());
        }
    }
}
