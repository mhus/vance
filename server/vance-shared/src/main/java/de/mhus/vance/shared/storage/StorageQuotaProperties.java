package de.mhus.vance.shared.storage;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.storage.quota.*} — tunables for the per-tenant storage
 * quota. The service owns three TTLs with distinct purposes:
 *
 * <ul>
 *   <li>{@code settingsTtl} — how long the read of
 *       {@code storage.quota.maxBytes} from the tenant's {@code _tenant}
 *       project is trusted. Short enough that a raised limit takes
 *       effect while the admin is still looking at the screen.</li>
 *   <li>{@code precheckTtl} — how long the result of the
 *       {@code db.storage_data.aggregate(...)} aggregation is trusted
 *       for the pre-check. Tightened relative to the settings cache
 *       because multi-pod race tolerance depends on it.</li>
 *   <li>{@code usageTtl} — how long the status payload for the UI is
 *       trusted. Looser; the insights tab refreshes on user action.</li>
 * </ul>
 *
 * <p>Pattern follows {@code HomesProperties}, {@code WorkspaceProperties},
 * {@code SchemaMigrationProperties}.
 */
@Data
@ConfigurationProperties(prefix = "vance.storage.quota")
public class StorageQuotaProperties {

    /** Settings cache TTL. Default 30s — same shape as LLM-Budget settings cache. */
    private Duration settingsTtl = Duration.ofSeconds(30);

    /** Pre-check aggregation cache TTL. Default 5s — tight enough for multi-pod race tolerance. */
    private Duration precheckTtl = Duration.ofSeconds(5);

    /** UI status cache TTL. Default 30s — UI refresh on user action. */
    private Duration usageTtl = Duration.ofSeconds(30);
}
