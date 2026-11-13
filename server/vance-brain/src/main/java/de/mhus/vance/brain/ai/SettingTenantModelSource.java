package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads {@value #KEY} from the {@code _tenant} project of the tenant. The key is in the
 * protected {@code vancetope.*} namespace: only the anus operator shell can set it (through the
 * {@code force} family), and the regular read path never shows it, so it is read with
 * {@code forceGetStringValue}. Values: {@code central} (default) or {@code bundled}.
 * {@value #CUSTOM_KEY} in the same place is the lock on the tenant's own models: {@code allowed}
 * (default) or {@code denied}.
 *
 * <p>Cached for {@link #TTL}: the setting is written by a separate process (anus), and the cache
 * is what carries the change to every brain pod.
 */
@Slf4j
@Component
public class SettingTenantModelSource implements TenantModelSource {

    /** Setting key, {@code _tenant} project of the tenant. */
    public static final String KEY = "vancetope.model.source";

    /** Setting key of the own-models lock, {@code _tenant} project of the tenant. */
    public static final String CUSTOM_KEY = "vancetope.model.custom";

    /** Value of {@link #CUSTOM_KEY} that confines the tenant to the bundled catalog. */
    public static final String DENIED = "denied";

    /** Value that makes the tenant read the classpath catalog. */
    public static final String BUNDLED = "bundled";

    static final Duration TTL = Duration.ofSeconds(30);

    private record Entry(boolean classpath, boolean ownModels, long loadedAtNanos) {}

    private final SettingService settings;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SettingTenantModelSource(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public boolean usesClasspathCatalog(String tenantId) {
        return entry(tenantId).classpath();
    }

    @Override
    public boolean ownModelsAllowed(String tenantId) {
        return entry(tenantId).ownModels();
    }

    private Entry entry(String tenantId) {
        long now = System.nanoTime();
        Entry cached = cache.get(tenantId);
        if (cached != null && now - cached.loadedAtNanos() < TTL.toNanos()) {
            return cached;
        }
        Entry loaded;
        try {
            loaded = new Entry(BUNDLED.equals(read(tenantId, KEY)), !DENIED.equals(read(tenantId, CUSTOM_KEY)), now);
        } catch (RuntimeException e) {
            // Store hiccup: keep the last answer rather than flip the catalog of a tenant.
            log.warn("Model settings of tenant '{}' unreadable, keeping the last answer: {}", tenantId, e.toString());
            return cached != null ? cached : new Entry(false, true, now);
        }
        cache.put(tenantId, loaded);
        return loaded;
    }

    private @Nullable String read(String tenantId, String key) {
        String value = settings.forceGetStringValue(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
