package de.mhus.vance.brain.trillian.exposure;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads and caches the exposure configuration
 * ({@code _vance/trillian/exposure.yaml}) of every Trillian of a tenant.
 * The document lives in the Trillian's hub project — a direct read, no
 * {@code _tenant} cascade on purpose: one tenant-level fallback would hand
 * the same {@code exposedName} to every Trillian without an own config, and
 * a name that resolves to several accounts is worse than a name that
 * resolves to none.
 *
 * <p>Caching follows the registry pattern ({@code McpProjectAccessService}):
 * memoised per {@code (tenant, account)}, evicted by the
 * {@link RoutedDocumentChangedEvent} every write path publishes — the hub
 * project's name is {@code _user_<account>}, so the event's project segment
 * carries the account — and every entry expires after
 * {@link #CACHE_TTL}, because the door is stateless HTTP that any pod
 * serves and a pod that missed the event must not serve a revoked exposure
 * forever.
 *
 * <p>Failure semantics are fail-closed, the same in every shape: a missing
 * document, a read error or a malformed document all mean "not exposed" —
 * with a WARN for the malformed case, because that one is an operator
 * mistake worth seeing.
 */
@Service
@Slf4j
public class TrillianExposureService {

    /** Document path of the exposure config inside the Trillian's hub. */
    public static final String CONFIG_PATH = "_vance/trillian/exposure.yaml";

    /** Upper bound on how long a pod that missed the change event serves an outdated config. */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DocumentService documentService;
    private final UserService userService;
    private final Clock clock;

    /** Parsed config per {@code tenant\0account}; empty = not exposed (negative cache included). */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<TrillianExposureConfig> config, Instant expiresAt) {}

    /** A Trillian as the directory shows it: account plus its exposure configuration. */
    public record Exposed(String account, TrillianExposureConfig config) {}

    @Autowired
    public TrillianExposureService(DocumentService documentService, UserService userService) {
        this(documentService, userService, Clock.systemUTC());
    }

    /** Test seam: controllable clock for the TTL. */
    TrillianExposureService(DocumentService documentService, UserService userService, Clock clock) {
        this.documentService = documentService;
        this.userService = userService;
        this.clock = clock;
    }

    /** The exposure config of one Trillian account, or empty = not exposed. */
    public Optional<TrillianExposureConfig> configOf(String tenantId, String account) {
        String key = key(tenantId, account);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.config();

        Optional<TrillianExposureConfig> parsed = readAndParse(tenantId, account);
        cache.put(key, new Cached(parsed, now.plus(CACHE_TTL)));
        return parsed;
    }

    /** The Trillian behind an exposed name — or empty when no exposed Trillian carries it. */
    public Optional<Exposed> findByExposedName(String tenantId, String exposedName) {
        return directory(tenantId).stream()
                .filter(exposed -> exposed.config().exposedName().equals(exposedName))
                .findFirst();
    }

    /**
     * The exposure of one Trillian account as the door sees it — empty when
     * the account is not exposed, or its name is ambiguous. The gate for
     * every call on an existing conversation, so a revoked exposure takes
     * effect on running conversations too.
     */
    public Optional<Exposed> findByAccount(String tenantId, String account) {
        return directory(tenantId).stream()
                .filter(exposed -> exposed.account().equals(account))
                .findFirst();
    }

    /**
     * The exposed Trillians of the tenant, by account name — the directory.
     * Only active service accounts with the Trillian prefix count; an
     * archived or deleted Trillian is not exposed, whatever its hub says.
     *
     * <p>An {@code exposedName} carried by more than one Trillian exposes
     * none of them: a name that resolves to several accounts is worse than
     * a name that resolves to none — it would route a counterpart to
     * whichever account the user listing happens to return first, and a
     * second account could take over a name by copying it. WARN, because
     * that is an operator mistake worth seeing.
     */
    public List<Exposed> directory(String tenantId) {
        List<Exposed> candidates = userService.all(tenantId).stream()
                .filter(TrillianExposureService::isTrillian)
                .map(acc -> configOf(tenantId, acc.getName()).map(c -> new Exposed(acc.getName(), c)))
                .flatMap(Optional::stream)
                .toList();
        Map<String, Long> perName = candidates.stream()
                .collect(Collectors.groupingBy(exposed -> exposed.config().exposedName(), Collectors.counting()));
        List<Exposed> unambiguous = new ArrayList<>();
        for (Exposed exposed : candidates) {
            if (perName.get(exposed.config().exposedName()) > 1) {
                log.warn(
                        "trillian exposure: exposedName '{}' is carried by several Trillians in '{}'"
                                + " — '{}' is not exposed until the name is unique",
                        exposed.config().exposedName(),
                        tenantId,
                        exposed.account());
                continue;
            }
            unambiguous.add(exposed);
        }
        return unambiguous;
    }

    /** Per-Trillian head limit override; {@code 0} = the Nature's default. */
    public int headLimitOf(String tenantId, String account) {
        return configOf(tenantId, account)
                .map(TrillianExposureConfig::headLimit)
                .orElse(0);
    }

    /** Per-Trillian inactivity override; empty = the global setting applies. */
    public Optional<Duration> inactivityOf(String tenantId, String account) {
        int days = configOf(tenantId, account)
                .map(TrillianExposureConfig::inactivityDays)
                .orElse(0);
        return days > 0 ? Optional.of(Duration.ofDays(days)) : Optional.empty();
    }

    private Optional<TrillianExposureConfig> readAndParse(String tenantId, String account) {
        String hubProject = HomeBootstrapService.hubProjectName(account);
        try {
            Optional<String> content = documentService
                    .findByPath(tenantId, hubProject, CONFIG_PATH)
                    .map(documentService::readContent);
            if (content.isEmpty()) return Optional.empty();
            return Optional.of(TrillianExposureConfig.parse(content.get()));
        } catch (RuntimeException parseOrReadFailure) {
            log.warn(
                    "trillian exposure: config of '{}' is unreadable or malformed — not exposed: {}",
                    account,
                    parseOrReadFailure.getMessage());
            return Optional.empty();
        }
    }

    /** Drops the cached config when the hub document changes. */
    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (!CONFIG_PATH.equals(event.path())) return;
        if (!event.projectId().startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) return;
        String account = event.projectId().substring(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length());
        cache.remove(key(event.tenantId(), account));
        log.debug("trillian exposure: config changed, cache evicted for '{}/{}'", event.tenantId(), account);
    }

    private static boolean isTrillian(UserDocument user) {
        return user.isServiceAccount()
                && user.getStatus() == UserStatus.ACTIVE
                && user.getName() != null
                && user.getName().startsWith(TrillianSessionBootstrapper.ACCOUNT_PREFIX);
    }

    private static String key(@Nullable String tenantId, String account) {
        return tenantId + '\0' + account;
    }
}
