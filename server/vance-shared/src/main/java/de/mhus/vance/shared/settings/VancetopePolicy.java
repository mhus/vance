package de.mhus.vance.shared.settings;

import de.mhus.vance.shared.cache.TtlTenantCache;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Write protection for the {@code vancetope.*} namespace. Three layers (the first one
 * holds for every caller except the {@code force} family of the anus operator shell):
 *
 * <ol>
 *   <li><b>Hardcoded prefix</b> {@code vancetope.}: nothing below it is
 *       writable through {@link SettingService#set} — neither in
 *       {@code _tenant} nor in user projects. anus may write only the
 *       explicitly allowed meta keys ({@code vancetope.denied.*}) via
 *       {@code forceSet}.</li>
 *   <li><b>Tenant deny list</b>: setting {@code vancetope.denied.tenant} in
 *       the {@code _tenant} project blocks {@code set} into the
 *       {@code _tenant} project for the listed patterns.</li>
 *   <li><b>Project deny list</b>: setting {@code vancetope.denied.project}
 *       in the {@code _tenant} project blocks {@code set} into every other
 *       scope (any {@code referenceType=project} scope that is not
 *       {@code _tenant}, and think-process scopes) for the listed
 *       patterns.</li>
 * </ol>
 *
 * <p>{@link SettingService#forceSet} (and every force variant) bypasses the
 * lists. Only the hardcoded prefix protection remains — anus cannot write
 * hypothetical {@code vancetope.foo} system settings that are not part of
 * the deny-list mechanics.
 *
 * <p>The lists are read from the {@code _tenant} project and cached per
 * tenant for {@link #CACHE_TTL}. The TTL is what carries a change made by
 * anus — a separate process — to every brain pod; {@link #onSettingChanged}
 * additionally drops the entry at once in the process that wrote it.
 *
 * <p>Pattern grammar: comma-separated, each entry either an exact key or a
 * prefix with a {@code *} suffix. The same grammar as
 * {@link AgentSettingKeyPolicy} and {@link KitSettingKeyPolicy}, so every
 * deny list reads the same.
 */
@Component
@Slf4j
public class VancetopePolicy {

    /**
     * System key namespace. Every key with this prefix is blocked for {@code set}
     * and hidden from the regular read path. The anus operator shell writes them
     * through the {@code force} family, any key of the namespace.
     */
    public static final String SYSTEM_KEY_PREFIX = "vancetope.";

    /** Sub-prefix of the "meta" system keys: the deny lists that configure this policy. */
    public static final String META_DENY_PREFIX = "vancetope.denied.";

    /** Setting key of the tenant deny list. Lives in the {@code _tenant} project. */
    public static final String KEY_DENIED_TENANT = "vancetope.denied.tenant";

    /** Setting key of the project deny list. Lives in the {@code _tenant} project. */
    public static final String KEY_DENIED_PROJECT = "vancetope.denied.project";

    /** Hardcoded default for the tenant list when the setting is absent. */
    public static final String DEFAULT_DENIED_TENANT = "";

    /** Hardcoded default for the project list when the setting is absent. */
    public static final String DEFAULT_DENIED_PROJECT = "";

    /**
     * How long a tenant's deny lists are trusted. Bounds how long a change
     * written by anus takes to reach a brain pod — there is no cross-process
     * invalidation, and none is needed for an operator setting.
     */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final ObjectProvider<SettingService> settingServiceProvider;
    private final TtlTenantCache<String, DenyLists> cacheByTenant = new TtlTenantCache<>(CACHE_TTL);

    public VancetopePolicy(ObjectProvider<SettingService> settingServiceProvider) {
        this.settingServiceProvider = settingServiceProvider;
    }

    // ──────────────────── Public API ────────────────────

    /**
     * Whether {@code key} is in the {@code vancetope.*} system namespace. Read
     * and write protection both go through this test.
     */
    public static boolean isSystemKey(@Nullable String key) {
        return key != null && key.startsWith(SYSTEM_KEY_PREFIX);
    }

    /** Whether {@code key} is a "meta deny key" — a {@code vancetope.denied.*} setting that configures this policy. */
    public static boolean isMetaDenyKey(@Nullable String key) {
        return key != null && key.startsWith(META_DENY_PREFIX);
    }

    /**
     * Throws {@link SecretAccessDeniedException} when {@code set} (not
     * {@code forceSet}) has to refuse the write.
     *
     * @param key the setting key
     * @param referenceType {@code project} | {@code think-process}
     * @param referenceId project or think-process id
     * @param tenantId tenant whose deny lists apply
     */
    public void requireWritable(String tenantId, String referenceType, String referenceId, String key) {
        if (isSystemKey(key)) {
            log.warn(
                    "Refusing write to system-namespace setting '{}' on {}/{}:{} — use forceSet (anus)",
                    key,
                    tenantId,
                    referenceType,
                    referenceId);
            throw new SecretAccessDeniedException(
                    SecretAccessDeniedException.Reason.WRITE_SYSTEM_NAMESPACE,
                    key,
                    "Setting '" + key + "' is reserved: keys in the 'vancetope.*' namespace are operator-managed "
                            + "system settings and cannot be written through the standard set() path. "
                            + "Reason: '" + key + "' matches the system namespace prefix 'vancetope.'. "
                            + "Resolution: use forceSet from the anus admin shell to write this key, "
                            + "or rename the setting to a non-reserved namespace.");
        }
        DenyLists lists = lists(tenantId);
        boolean inTenantScope = SettingService.SCOPE_PROJECT.equals(referenceType)
                && HomeBootstrapService.TENANT_PROJECT_NAME.equals(referenceId);
        if (inTenantScope) {
            if (SettingKeyPatterns.matches(lists.tenant, key)) {
                log.warn("Refusing write to {}:{}:{} — blocked by tenant deny list", tenantId, referenceType, key);
                throw new SecretAccessDeniedException(
                        SecretAccessDeniedException.Reason.WRITE_TENANT_DENY_LIST,
                        key,
                        "Setting '" + key + "' is reserved: the tenant deny list forbids writing this key "
                                + "into the _tenant project. "
                                + "Reason: '" + key + "' matches a pattern in the vancetope.denied.tenant setting "
                                + "in the _tenant project. "
                                + "Resolution: edit vancetope.denied.tenant in the _tenant project via "
                                + "the anus admin shell (forceSet), or use a different key.");
            }
        } else {
            if (SettingKeyPatterns.matches(lists.project, key)) {
                log.warn("Refusing write to {}:{}:{} — blocked by project deny list", tenantId, referenceType, key);
                throw new SecretAccessDeniedException(
                        SecretAccessDeniedException.Reason.WRITE_PROJECT_DENY_LIST,
                        key,
                        "Setting '" + key + "' is reserved: the project deny list forbids writing this key "
                                + "into a user-created project. "
                                + "Reason: '" + key + "' matches a pattern in the vancetope.denied.project "
                                + "setting in the _tenant project. "
                                + "Resolution: edit vancetope.denied.project in the _tenant project via "
                                + "the anus admin shell (forceSet), or use a different key.");
            }
        }
    }

    /**
     * The current tenant deny list, for diagnostics and reports. Read via
     * {@code forceGetStringValue} — not through the regular cascade, which
     * filters {@code vancetope.*} out of the user view.
     */
    public List<String> tenantDenyPatterns(String tenantId) {
        return lists(tenantId).tenant;
    }

    /** The current project deny list, for diagnostics and reports. */
    public List<String> projectDenyPatterns(String tenantId) {
        return lists(tenantId).project;
    }

    /**
     * Drops the cached deny lists of {@code tenantId}, so the next check
     * reads them fresh.
     */
    public void refresh(String tenantId) {
        cacheByTenant.invalidate(tenantId);
    }

    /**
     * Notification that a setting was written or deleted. For meta deny keys
     * the cache entry is dropped.
     *
     * <p>Called by {@code SettingService} after every successful force write
     * and force delete. {@code set} refuses meta deny keys anyway, so only the
     * force path matters here. Other processes pick the change up after
     * {@link #CACHE_TTL}.
     */
    public void onSettingChanged(String tenantId, String key) {
        if (isMetaDenyKey(key)) {
            refresh(tenantId);
            log.trace("VancetopePolicy cache refreshed for tenant='{}' due to {}", tenantId, key);
        }
    }

    private DenyLists lists(String tenantId) {
        DenyLists lists = cacheByTenant.get(tenantId, () -> loadDenyLists(tenantId));
        return lists == null ? DenyLists.NONE : lists;
    }

    // ──────────────────── Internals ────────────────────

    private DenyLists loadDenyLists(String tenantId) {
        // Lazy resolve: VancetopePolicy is wired by Spring before SettingService
        // finishes its own construction, so we cannot hold a direct reference.
        // ObjectProvider gives us the same instance at runtime without
        // requiring a constructor cycle. Tests can construct VancetopePolicy
        // with a no-op provider that throws or returns null.
        SettingService settingService = settingServiceProvider.getObject();
        String tenantRaw = settingService.forceGetStringValue(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, KEY_DENIED_TENANT);
        String projectRaw = settingService.forceGetStringValue(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, KEY_DENIED_PROJECT);
        List<String> tenant = tenantRaw == null
                ? SettingKeyPatterns.parse(DEFAULT_DENIED_TENANT)
                : SettingKeyPatterns.parse(tenantRaw);
        List<String> project = projectRaw == null
                ? SettingKeyPatterns.parse(DEFAULT_DENIED_PROJECT)
                : SettingKeyPatterns.parse(projectRaw);
        log.trace("VancetopePolicy tenant='{}': tenant={} project={}", tenantId, tenant, project);
        return new DenyLists(tenant, project);
    }

    private record DenyLists(List<String> tenant, List<String> project) {
        static final DenyLists NONE = new DenyLists(List.of(), List.of());
    }
}
