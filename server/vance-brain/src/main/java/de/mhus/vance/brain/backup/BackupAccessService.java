package de.mhus.vance.brain.backup;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads and caches {@code _vance/config/backup-access.yaml} from the
 * tenant's {@code _tenant} project — the gate for the {@code backup}
 * integration-token profile ({@code planning/user-backup.md} §7.2).
 *
 * <p>Same shape as {@link de.mhus.vance.brain.mcpserver.McpGlobalAccessService}
 * but with inverted defaults: a <b>missing</b> file produces
 * {@link BackupAccessConfig#DEFAULT_ALLOWED} (feature on, no user
 * restriction). The inversion is deliberate — reading a doc is not the
 * user's first action, and a fresh tenant should not have to write YAML
 * before a backup can run. A <b>malformed</b> file, however, denies all
 * access with a WARN — anyone who wrote the file wanted something
 * specific, and a typo must not turn "lock down to alice" into
 * "everyone".
 *
 * <p>Cached per tenant with a 30 s TTL, invalidated by the
 * DocumentChanged event on the config path. Same trade-off as
 * {@code McpGlobalAccessService}: an exact cross-pod broadcast channel
 * for a one-line config is over-engineering, and the TTL bounds operator
 * surprise.
 */
@Service
@Slf4j
public class BackupAccessService {

    public static final String CONFIG_PATH = "_vance/config/backup-access.yaml";

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DocumentService documentService;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(BackupAccessConfig config, Instant expiresAt) {}

    @Autowired
    public BackupAccessService(DocumentService documentService) {
        this(documentService, Clock.systemUTC());
    }

    BackupAccessService(DocumentService documentService, Clock clock) {
        this.documentService = documentService;
        this.clock = clock;
    }

    /** The current policy for the tenant. Never null — defaults to {@link BackupAccessConfig#DEFAULT_ALLOWED}. */
    public BackupAccessConfig load(String tenantId) {
        Instant now = clock.instant();
        Cached cached = cache.get(tenantId);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.config();
        BackupAccessConfig parsed = readAndParse(tenantId);
        cache.put(tenantId, new Cached(parsed, now.plus(CACHE_TTL)));
        return parsed;
    }

    private BackupAccessConfig readAndParse(String tenantId) {
        Optional<LookupResult> hit;
        try {
            hit = documentService.lookupCascade(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CONFIG_PATH);
        } catch (RuntimeException ex) {
            log.warn("backup-access: reading config failed for tenant '{}' — denying: {}", tenantId, ex.toString());
            return denyOnFailure();
        }
        if (hit.isEmpty()) return BackupAccessConfig.DEFAULT_ALLOWED;
        try {
            return BackupAccessConfig.parse(hit.get().content());
        } catch (RuntimeException ex) {
            log.warn("backup-access: malformed config in tenant '{}' — denying: {}", tenantId, ex.getMessage());
            return denyOnFailure();
        }
    }

    /** The policy returned when reading or parsing fails. Different from {@link BackupAccessConfig#DEFAULT_ALLOWED} on purpose. */
    private static BackupAccessConfig denyOnFailure() {
        // enabled=false; the user list does not matter — nobody passes the
        // enabled gate. idleDays stays at the default so a future recovery
        // (TTL expiry, manual cache flush) does not silently widen the
        // ceiling.
        return new BackupAccessConfig(false, java.util.List.of(), 90);
    }

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (CONFIG_PATH.equals(event.path()) && HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
            cache.remove(event.tenantId());
            log.debug("backup-access: config changed, cache evicted for tenant '{}'", event.tenantId());
        }
    }
}
