package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads {@value #KEY} from the {@code _tenant} project of the tenant. The key is in the
 * protected {@code vancetope.*} namespace: only the anus operator shell can set it (through the
 * {@code force} family), and the regular read path never shows it, so it is read with
 * {@code forceGetStringValue}. Values: {@code central} (default) or {@code bundled}.
 *
 * <p>Cached for {@link #TTL}: the setting is written by a separate process (anus), and the cache
 * is what carries the change to every brain pod.
 */
@Slf4j
@Component
public class SettingTenantModelSource implements TenantModelSource {

    /** Setting key, {@code _tenant} project of the tenant. */
    public static final String KEY = "vancetope.model.source";

    /** Value that makes the tenant read the classpath catalog. */
    public static final String BUNDLED = "bundled";

    static final Duration TTL = Duration.ofSeconds(30);

    private record Entry(boolean classpath, long loadedAtNanos) {}

    private final SettingService settings;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SettingTenantModelSource(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public boolean usesClasspathCatalog(String tenantId) {
        long now = System.nanoTime();
        Entry cached = cache.get(tenantId);
        if (cached != null && now - cached.loadedAtNanos() < TTL.toNanos()) {
            return cached.classpath();
        }
        boolean classpath;
        try {
            String value = settings.forceGetStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, KEY);
            classpath = value != null && BUNDLED.equals(value.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            // Store hiccup: keep the last answer rather than flip the catalog of a tenant.
            log.warn("Model source of tenant '{}' unreadable, keeping the last answer: {}", tenantId, e.toString());
            return cached != null && cached.classpath();
        }
        cache.put(tenantId, new Entry(classpath, now));
        return classpath;
    }
}
