package de.mhus.vance.shared.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link StorageQuotaProperties} as a Spring bean so {@link
 * MongoStorageService} and {@link StorageQuotaService} can be auto-wired
 * with the tunables from {@code application.yml} (or env vars). Mirrors
 * the pattern used by {@code VanceRedisConfig} for {@code vance.redis.*}.
 *
 * <p>The class is intentionally empty beyond the annotation — there are no
 * beans to construct here, the properties object itself is the bean.
 */
@Configuration
@EnableConfigurationProperties(StorageQuotaProperties.class)
public class StorageQuotaConfig {}
