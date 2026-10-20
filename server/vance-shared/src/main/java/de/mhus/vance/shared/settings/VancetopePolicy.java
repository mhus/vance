package de.mhus.vance.shared.settings;

import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Schreibschutz-Policy für den {@code vancetope.*}-Namespace. Drei Schichten:
 *
 * <ol>
 *   <li><b>Hartkodiertes Prefix</b> {@code vancetope.}: alles darunter ist
 *       über {@link SettingService#set} nicht schreibbar — weder in
 *       {@code _tenant} noch in User-Projekten. anus darf via
 *       {@code forceSet} nur die explizit erlaubten Meta-Keys
 *       ({@code vancetope.denied.*}) setzen.</li>
 *   <li><b>Tenant-Deny-Liste</b>: Setting {@code vancetope.denied.tenant} im
 *       {@code _tenant}-Projekt blockiert {@code set} im
 *       {@code _tenant}-Projekt für die gelisteten Patterns.</li>
 *   <li><b>Project-Deny-Liste</b>: Setting {@code vancetope.denied.project}
 *       im {@code _tenant}-Projekt blockiert {@code set} in allen
 *       User-Projekten (jedem {@code referenceType=project}-Scope, der nicht
 *       {@code _tenant} ist) für die gelisteten Patterns.</li>
 * </ol>
 *
 * <p>{@link SettingService#forceSet} (und alle Force-Varianten) umgehen die
 * Listen. Nur der hartkodierte Prefix-Schutz bleibt — anus darf damit keine
 * hypothetischen {@code vancetope.foo}-System-Settings setzen, die nicht zur
 * Deny-Listen-Mechanik gehören.
 *
 * <p>Die Listen werden per {@link #refresh} aus dem {@code _tenant}-Projekt
 * geladen, gecacht pro Tenant. {@link SettingService} ruft
 * {@link #onSettingChanged} nach jedem erfolgreichen Write — bei
 * Meta-Deny-Keys wird der Cache geleert, damit der nächste
 * {@link #requireWritable}-Call die frische Liste zieht.
 *
 * <p>Settings-Pattern-Grammatik: comma-separated, jeder Eintrag entweder ein
 * exakter Key oder ein Prefix mit {@code *}-Suffix. Dieselbe Grammatik wie
 * {@link AgentSettingKeyPolicy} und {@link KitSettingKeyPolicy}, damit alle
 * Deny-Listen gleich lesbar sind.
 */
@Component
@Slf4j
public class VancetopePolicy {

    /**
     * System-Key-Namespace. Alle Keys mit diesem Prefix sind über {@code set}
     * immer blockiert. anus darf via {@code forceSet} nur Keys unter
     * {@link #META_DENY_PREFIX} setzen — das sind die Settings, die diese
     * Policy selbst konfigurieren.
     */
    public static final String SYSTEM_KEY_PREFIX = "vancetope.";

    /**
     * Sub-Prefix der "Meta"-System-Keys (die Deny-Listen selbst). Diese dürfen
     * via {@code forceSet} gesetzt werden, weil sonst die Listen nie
     * einrichtbar wären. Alles andere unter {@link #SYSTEM_KEY_PREFIX} bleibt
     * für {@code forceSet} ebenfalls blockiert.
     */
    public static final String META_DENY_PREFIX = "vancetope.denied.";

    /** Setting-Key der Tenant-Deny-Liste. Lebt im {@code _tenant}-Projekt. */
    public static final String KEY_DENIED_TENANT = "vancetope.denied.tenant";

    /** Setting-Key der Project-Deny-Liste. Lebt im {@code _tenant}-Projekt. */
    public static final String KEY_DENIED_PROJECT = "vancetope.denied.project";

    /** Hartkodierter Default für die Tenant-Liste, falls das Setting fehlt. */
    public static final String DEFAULT_DENIED_TENANT = "";

    /** Hartkodierter Default für die Project-Liste, falls das Setting fehlt. */
    public static final String DEFAULT_DENIED_PROJECT = "";

    private final ObjectProvider<SettingService> settingServiceProvider;
    private final ConcurrentMap<String, DenyLists> cacheByTenant = new ConcurrentHashMap<>();

    public VancetopePolicy(ObjectProvider<SettingService> settingServiceProvider) {
        this.settingServiceProvider = settingServiceProvider;
    }

    // ──────────────────── Public API ────────────────────

    /**
     * Ob {@code key} im {@code vancetope.*}-System-Namespace liegt. Lese- und
     * Schreibschutz laufen beide über diesen Test.
     */
    public static boolean isSystemKey(@Nullable String key) {
        return key != null && key.startsWith(SYSTEM_KEY_PREFIX);
    }

    /**
     * Ob {@code key} ein "Meta-Deny-Key" ist — eine {@code vancetope.denied.*}-
     * Einstellung, die diese Policy selbst konfiguriert. Diese dürfen via
     * {@code forceSet} gesetzt werden; alle anderen {@code vancetope.*}-Keys
     * bleiben für {@code forceSet} blockiert.
     */
    public static boolean isMetaDenyKey(@Nullable String key) {
        return key != null && key.startsWith(META_DENY_PREFIX);
    }

    /**
     * Throws {@link SecretAccessDeniedException} wenn {@code set} (nicht
     * {@code forceSet}) den Schreibvorgang ablehnen muss.
     *
     * @param key the setting key
     * @param referenceType {@code project} | {@code think-process}
     * @param referenceId Projekt- oder Think-Process-Id
     * @param tenantId Tenant, zu dem die Deny-Listen geladen werden
     */
    public void requireWritable(String tenantId, String referenceType, String referenceId, String key) {
        if (isSystemKey(key)) {
            log.warn(
                    "Refusing write to system-namespace setting '{}' on {}/{}:{} — use forceSet for vancetope.denied.*",
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
        DenyLists lists = cacheByTenant.computeIfAbsent(tenantId, this::loadDenyLists);
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
     * Throws {@link SecretAccessDeniedException} wenn {@code forceSet} den
     * Schreibvorgang ablehnen muss. Einziger Schutz: das
     * {@code vancetope.*}-Prefix, und auch da nur für nicht-Meta-Keys.
     */
    public void requireForceWritable(String key) {
        if (isSystemKey(key) && !isMetaDenyKey(key)) {
            log.warn("Refusing force-write to non-meta system-namespace setting '{}'", key);
            throw new SecretAccessDeniedException(
                    SecretAccessDeniedException.Reason.WRITE_FORCE_NON_META,
                    key,
                    "Setting '" + key + "' is reserved: forceSet may only write 'vancetope.denied.*' keys, "
                            + "which are the configurable deny-list settings themselves. "
                            + "Reason: '" + key + "' is in the vancetope.* system namespace but is not a "
                            + "deny-list configuration key. "
                            + "Resolution: this key is not operator-configurable through the settings API; "
                            + "it would require a future setSystem() escape hatch to write.");
        }
    }

    /**
     * Liest die aktuelle Tenant-Deny-Liste für Diagnostik / Reports. Über
     * {@code forceGet} — nicht über die normale Cascade, weil
     * {@code vancetope.*} aus der User-Cascade herausgefiltert wird.
     */
    public List<String> tenantDenyPatterns(String tenantId) {
        return cacheByTenant.computeIfAbsent(tenantId, this::loadDenyLists).tenant;
    }

    /** Liest die aktuelle Project-Deny-Liste für Diagnostik / Reports. */
    public List<String> projectDenyPatterns(String tenantId) {
        return cacheByTenant.computeIfAbsent(tenantId, this::loadDenyLists).project;
    }

    /**
     * Verwirft die gecachte Deny-Liste für {@code tenantId}. Wird von
     * {@link SettingService} nach jedem erfolgreichen Write eines
     * Meta-Deny-Keys aufgerufen.
     */
    public void refresh(String tenantId) {
        cacheByTenant.remove(tenantId);
    }

    /**
     * Notification, dass ein Setting geändert wurde. Bei Meta-Deny-Keys wird
     * der Cache geleert.
     *
     * <p>Wird vom {@code SettingService} nach jedem erfolgreichen
     * {@code forceSet} aufgerufen. Da {@code set} Meta-Deny-Keys ohnehin
     * ablehnt, ist nur der Force-Pfad hier relevant.
     */
    public void onSettingChanged(String tenantId, String key) {
        if (isMetaDenyKey(key)) {
            refresh(tenantId);
            log.debug("VancetopePolicy cache refreshed for tenant='{}' due to {}", tenantId, key);
        }
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
        log.debug("VancetopePolicy tenant='{}': tenant={} project={}", tenantId, tenant, project);
        return new DenyLists(tenant, project);
    }

    private record DenyLists(List<String> tenant, List<String> project) {}
}
