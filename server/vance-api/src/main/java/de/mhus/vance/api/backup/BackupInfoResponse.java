package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code GET /brain/{tenant}/backup/info} — the first call
 * {@code vance-liver} makes on a fresh start. Tells the client the current
 * token expiry, the server's manifest format, and why a token that should
 * work has been turned away.
 *
 * <p>{@code allowed} is a precomputed answer to "would my token survive
 * the next call?" — the client does not need to do its own policy check.
 * A {@code false} here is the explanation that turns a mysterious 403
 * into a useful error message.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupInfoResponse {

    private boolean allowed;

    /** Why a token that should work is being refused. {@code null} when {@code allowed}. */
    private @Nullable String denialReason;

    /** Server version, free-form (e.g. {@code "0.7.0-SNAPSHOT"}). Useful in the tool's status output. */
    private String serverVersion;

    /** Manifest format this server emits. v1 today; bumped on a breaking schema change. */
    private int manifestFormat;

    /**
     * Current token expiry, milliseconds since epoch. The token is
     * sliding-lifetime, so this moves forward with each successful
     * {@code POST /backup/runs}. {@code null} when the token has no
     * expiresAt (older fixed-end tokens).
     */
    private @Nullable Long tokenExpiresAtMs;

    /** Tenant idle-window ceiling from the policy (days). The token's own idleDays cannot exceed this. */
    private int idleDaysCeiling;

    /** Manifest format this client emits (from the User-Agent or a config flag). Echoed for round-trip debugging. */
    private @Nullable String clientFormat;

    /** Surface list as the server sees it — echo of what the token's profile declares. */
    private List<String> allowedSurfaces;
}
