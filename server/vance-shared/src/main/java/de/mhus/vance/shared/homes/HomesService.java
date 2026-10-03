package de.mhus.vance.shared.homes;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Provides a {@code HOME} per scope for agent-driven subprocesses — the fix
 * for {@code mhus/vance#63}, where every project and tenant shared the
 * process home on the pod.
 *
 * <p><b>Layout:</b> {@code <homes-root>/<tenant>/<scope-key>} where the scope
 * key reuses the permission system's vocabulary ({@code _tenant},
 * {@code _user_<login>}, otherwise the project name). The tree is deliberately
 * a sibling of the workspace storage, not part of it: nothing that lists,
 * serves or exports workspace content can reach a home, and no filter has to
 * remember to exclude it.
 *
 * <p><b>Scope from settings, key from code.</b> The {@code home-scope} setting
 * picks the granularity ({@code project | tenant | user}); the directory name
 * is derived from that enum, never from setting text. The setting is resolved
 * on the project cascade <b>without</b> the think-process scope — a
 * {@code setting_set} on process scope must not be able to point a process at
 * another scope's home (the self-approve bypass the Wowbagger model gate
 * documents for {@code wowbagger.allowed-models}).
 *
 * <p><b>Homes are disposable state (persistence A).</b> Caches and
 * runtime-written config live here and may vanish with the pod; credentials
 * are re-materialised from their grants, never kept as the only copy. See
 * {@code planning/home-isolation.md} §6.
 */
@Service
@Slf4j
public class HomesService {

    /** Setting key on the project cascade that picks {@link HomeScope}. */
    public static final String SETTING_HOME_SCOPE = "home-scope";

    /** How long a size measurement is reused before the tree is walked again. */
    private static final Duration SIZE_PROBE_INTERVAL = Duration.ofMinutes(5);

    private final HomesProperties properties;
    private final WorkspaceProperties workspaceProperties;
    private final SettingService settingService;

    /** Cached size measurement per scope key ({@code tenant/key}). */
    private final Map<String, SizeProbe> sizeProbes = new ConcurrentHashMap<>();

    public HomesService(
            HomesProperties properties, WorkspaceProperties workspaceProperties, SettingService settingService) {
        this.properties = properties;
        this.workspaceProperties = workspaceProperties;
        this.settingService = settingService;
    }

    private record SizeProbe(long bytes, Instant measuredAt) {}

    /**
     * Root of the homes tree: the configured {@code vance.homes.root}, else
     * the workspace root's sibling {@code homes/}.
     */
    public Path root() {
        if (StringUtils.isNotBlank(properties.getRoot())) {
            return Path.of(properties.getRoot()).toAbsolutePath().normalize();
        }
        Path workspaceRoot =
                Path.of(workspaceProperties.getRoot()).toAbsolutePath().normalize();
        Path parent = workspaceRoot.getParent();
        return (parent != null ? parent : workspaceRoot).resolve("homes");
    }

    /**
     * The scope a project's homes resolve under. Falls back to {@link
     * HomeScope#PROJECT} — the narrowest isolation — when the setting is
     * absent or unparsable, so a typo can never widen sharing.
     */
    public HomeScope scopeOf(String tenantId, String projectId) {
        String value = settingService.getStringValueCascade(tenantId, projectId, null, SETTING_HOME_SCOPE);
        HomeScope parsed = HomeScope.parse(value);
        if (parsed != null) {
            return parsed;
        }
        if (value != null && !value.isBlank()) {
            log.warn(
                    "unknown value '{}' for setting '{}' on {}/{} — using project scope",
                    value,
                    SETTING_HOME_SCOPE,
                    tenantId,
                    projectId);
        }
        return HomeScope.PROJECT;
    }

    /**
     * Directory name for the given context: the project name, the
     * {@code _tenant} key or the user's hub key — never setting text. A
     * {@code user} scope without a user falls back to the project's own home
     * (still isolated, only less shared).
     */
    public String scopeKey(String tenantId, String projectId, @Nullable String userName) {
        return switch (scopeOf(tenantId, projectId)) {
            case PROJECT -> projectId;
            case TENANT -> HomeBootstrapService.TENANT_PROJECT_NAME;
            case USER -> StringUtils.isBlank(userName) ? projectId : HomeBootstrapService.hubProjectName(userName);
        };
    }

    /** The home directory for one scope key. Does not create anything. */
    public Path homeDir(String tenantId, String scopeKey) {
        requireSegment(tenantId, "tenant");
        requireSegment(scopeKey, "home scope key");
        return root().resolve(tenantId).resolve(scopeKey);
    }

    /**
     * The {@code HOME} for a subprocess of the given context — created on
     * demand and size-checked. This is the path that goes into the sealed
     * child environment.
     */
    public Path ensure(String tenantId, String projectId, @Nullable String userName) {
        String scopeKey = scopeKey(tenantId, projectId, userName);
        Path dir = homeDir(tenantId, scopeKey);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new HomesException("Cannot create home directory '" + dir + "': " + e.getMessage(), e);
        }
        enforceSizeLimit(tenantId, scopeKey, dir);
        return dir;
    }

    /** Whether a home directory exists for the scope key (maintenance view). */
    public boolean exists(String tenantId, String scopeKey) {
        return Files.isDirectory(homeDir(tenantId, scopeKey));
    }

    /**
     * Removes a home directory. Maintenance-only ({@code project delete},
     * {@code user delete}) — a project owns exactly its project-scoped home;
     * shared homes are kept and reported via the handler's {@code deleteNote}.
     *
     * @return {@code 1} when a directory was removed, {@code 0} otherwise
     */
    public long delete(String tenantId, String scopeKey) {
        Path dir = homeDir(tenantId, scopeKey);
        sizeProbes.remove(tenantId + "/" + scopeKey);
        if (!Files.exists(dir)) {
            return 0;
        }
        deleteRecursively(dir);
        return 1;
    }

    /**
     * Moves a home directory to a new scope key. Maintenance-only
     * ({@code project rename}, {@code user rename}) — the scope key follows the
     * name, so the home has to follow.
     *
     * @return {@code 1} when a directory was moved, {@code 0} otherwise
     */
    public long rename(String tenantId, String scopeKey, String newScopeKey) {
        Path from = homeDir(tenantId, scopeKey);
        if (!Files.exists(from)) {
            return 0;
        }
        Path to = homeDir(tenantId, newScopeKey);
        if (Files.exists(to)) {
            throw new HomesException("target home directory already exists: " + to);
        }
        sizeProbes.remove(tenantId + "/" + scopeKey);
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new HomesException("Cannot move home directory '" + from + "' to '" + to + "': " + e.getMessage(), e);
        }
        return 1;
    }

    /**
     * Size guard. Measured at most every {@link #SIZE_PROBE_INTERVAL} per
     * scope, so a busy exec loop does not walk the tree per job — the guard is
     * coarse by design, the budget generous. Over budget means the subprocess
     * does not start; the home itself is the agent's to clean.
     */
    private void enforceSizeLimit(String tenantId, String scopeKey, Path dir) {
        long maxBytes = properties.getMaxBytes();
        if (maxBytes <= 0) {
            return;
        }
        String cacheKey = tenantId + "/" + scopeKey;
        Instant now = Instant.now();
        SizeProbe probe = sizeProbes.get(cacheKey);
        if (probe == null || probe.measuredAt().isBefore(now.minus(SIZE_PROBE_INTERVAL))) {
            probe = new SizeProbe(sizeOf(dir), now);
            sizeProbes.put(cacheKey, probe);
        }
        if (probe.bytes() > maxBytes) {
            throw new HomesException("home directory '" + dir + "' holds " + probe.bytes()
                    + " bytes, over the configured budget of " + maxBytes
                    + " (vance.homes.max-bytes) — clean the home (caches live there),"
                    + " raise the budget, or disable it with 0");
        }
    }

    private static long sizeOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("cannot measure home directory {}: {}", dir, e.toString());
            return 0L;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new HomesException("Cannot delete '" + path + "': " + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new HomesException("Cannot delete '" + dir + "': " + e.getMessage(), e);
        }
    }

    /**
     * Scope keys and tenant names become path segments, so they must behave
     * like one: no separators, no traversal. Project names are fachliche
     * Schlüssel and should never carry either — this is the cheap check that
     * keeps that assumption from becoming a path escape.
     */
    private static void requireSegment(String value, String what) {
        if (StringUtils.isBlank(value)) {
            throw new HomesException(what + " is required");
        }
        if (value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0
                || ".".equals(value)
                || "..".equals(value)) {
            throw new HomesException(what + " must be a single path segment: '" + value + "'");
        }
    }
}
