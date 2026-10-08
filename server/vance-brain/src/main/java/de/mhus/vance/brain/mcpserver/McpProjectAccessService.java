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
 * Loads and caches the per-project MCP access config
 * ({@code _vance/config/mcp-access.yaml}) for the project-scoped MCP
 * surface. Reads go through {@link DocumentService#lookupCascade} with
 * the usual reading — project tier first, {@code _tenant} as fallback —
 * so an operator can pre-regulate every project of a tenant from the
 * system project and a single project can override with its own config.
 *
 * <p>Caching follows the established registry pattern
 * ({@code ServerToolDocumentListener}/{@code ServerToolRegistry}): the
 * parsed config is memoised per {@code (tenant, project)} and invalidated
 * by the {@link RoutedDocumentChangedEvent} that every write path
 * publishes. A change in {@code _tenant} drops every entry of the tenant,
 * because each project without its own config cached the tenant fallback
 * under its own key.
 *
 * <p>The event alone is not enough for coherence: the router broadcasts
 * only {@code _tenant} changes; a project change reaches just the writing
 * pod and the project's lease holder. This surface, however, is stateless
 * HTTP that any pod serves — so every entry also expires after
 * {@link #CACHE_TTL}. That bounds how long a revoked or narrowed config
 * stays effective on an uninformed pod, at the cost of one cascade lookup
 * per project and TTL. Idempotent, write-free, catches its own exceptions.
 *
 * <p>Failure semantics are fail-closed: a missing document denies, and
 * a malformed document denies <em>and</em> warns — a broken config must
 * open nothing, and the MCP client still gets a clean protocol response
 * instead of a 5xx.
 */
@Service
@Slf4j
public class McpProjectAccessService {

    /** Document path of the config inside the project (cascade: project → _tenant). */
    public static final String CONFIG_PATH = "_vance/config/mcp-access.yaml";

    /**
     * Upper bound on how long a pod that missed the change event (neither
     * writer nor lease holder) keeps serving an outdated config.
     */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DocumentService documentService;
    private final Clock clock;

    /** Parsed config per {@code tenant\0project}; empty = deny (negative cache included). */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(McpProjectAccessConfig config, Instant expiresAt) {}

    @Autowired
    public McpProjectAccessService(DocumentService documentService) {
        this(documentService, Clock.systemUTC());
    }

    /** Test seam: controllable clock for the TTL. */
    McpProjectAccessService(DocumentService documentService, Clock clock) {
        this.documentService = documentService;
        this.clock = clock;
    }

    /**
     * The config entry that applies to the caller, or {@code null} =
     * deny. First-match-wins in document order (see
     * {@link McpProjectAccessConfig#resolve}).
     */
    public McpProjectAccessConfig.@Nullable Entry resolveEntry(
            String tenantId, String projectId, @Nullable String tokenId) {
        return load(tenantId, projectId).resolve(tokenId);
    }

    private McpProjectAccessConfig load(String tenantId, String projectId) {
        String key = key(tenantId, projectId);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.config();

        McpProjectAccessConfig parsed = readAndParse(tenantId, projectId);
        cache.put(key, new Cached(parsed, now.plus(CACHE_TTL)));
        return parsed;
    }

    private McpProjectAccessConfig readAndParse(String tenantId, String projectId) {
        Optional<LookupResult> hit;
        try {
            hit = documentService.lookupCascade(tenantId, projectId, CONFIG_PATH);
        } catch (RuntimeException ex) {
            log.warn("mcp-access: reading config failed for '{}/{}' — denying: {}", tenantId, projectId, ex.toString());
            return McpProjectAccessConfig.EMPTY;
        }
        if (hit.isEmpty()) return McpProjectAccessConfig.EMPTY;
        try {
            return McpProjectAccessConfig.parse(hit.get().content());
        } catch (RuntimeException ex) {
            log.warn("mcp-access: malformed config in '{}/{}' — denying: {}", tenantId, projectId, ex.getMessage());
            return McpProjectAccessConfig.EMPTY;
        }
    }

    /**
     * Drops the cached config when the document changes. A {@code _tenant}
     * change drops the whole tenant: every project without its own config
     * holds the tenant fallback under its own key.
     */
    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (!CONFIG_PATH.equals(event.path())) return;
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
            String tenantPrefix = event.tenantId() + '\0';
            cache.keySet().removeIf(key -> key.startsWith(tenantPrefix));
            log.debug("mcp-access: tenant config changed, cache evicted for tenant '{}'", event.tenantId());
            return;
        }
        cache.remove(key(event.tenantId(), event.projectId()));
        log.debug("mcp-access: config changed, cache evicted for '{}/{}'", event.tenantId(), event.projectId());
    }

    private static String key(String tenantId, String projectId) {
        return tenantId + '\0' + projectId;
    }
}
