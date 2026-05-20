package de.mhus.vance.api.daemon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of one registered foot daemon, surfaced by the
 * tenant-admin endpoint {@code GET /brain/{tenant}/admin/daemons}.
 *
 * <p>Tool names are exposed (admin needs to know what the daemon offers
 * to configure {@code foot_daemon} ServerTools), but no invoke metadata
 * is leaked here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("daemon")
public class DaemonInfoDto {

    private String projectId;
    private String daemonName;

    /**
     * Lifecycle state. {@code "online"} → WS attached and accepting
     * invokes; {@code "stale"} → WS closed, entry kept around for
     * reconnect-flicker grace, sub-tools still listed but invokes
     * fail until reconnect. {@code "offline"} daemons never appear
     * in this listing (the registry purges them after the TTL).
     */
    private String status;

    /** ISO-8601 UTC; first time this daemon registered in the current process. */
    private String registeredAt;

    /** ISO-8601 UTC; last activity (message in either direction). */
    private String lastSeenAt;

    /** ISO-8601 UTC of the disconnect that started the stale window; null when online. */
    private @Nullable String disconnectedAt;

    /** Tool names announced by the daemon — does not include input schemas. */
    @Builder.Default
    private List<String> tools = new ArrayList<>();
}
