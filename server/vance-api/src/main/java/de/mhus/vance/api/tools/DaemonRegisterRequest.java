package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sent by a foot daemon ({@code profile=daemon}) right after WELCOME to
 * claim its slot in the brain's project-scoped daemon registry and
 * publish its tool manifest in one envelope.
 *
 * <p>Unlike {@link ClientToolRegisterRequest}, this carries
 * {@link #projectId} + {@link #daemonName} and does <strong>not</strong>
 * require a bound session — daemons live outside the session model.
 * The brain registers the connection as
 * {@code (tenantId, projectId, daemonName) → wsSession} for cross-session
 * tool routing (see {@code planning/foot-daemon-tools.md}).
 *
 * <p>Re-send (same WS) is allowed and replaces the manifest in place —
 * supports hot-plug of foot plugins on a running daemon.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class DaemonRegisterRequest {

    /**
     * Target project. Must be a regular project or {@code _tenant}; the
     * brain rejects user-scoped projects ({@code _user_*}) because they
     * have no stable home pod for a daemon to bind to.
     */
    private String projectId;

    /**
     * Project-unique daemon identifier (e.g. {@code "server-prod-01"}).
     * Pattern: lowercase alpha-num + {@code _-}, ≤ 64 chars.
     */
    private String daemonName;

    /** Tool manifest exposed by this daemon. */
    @Builder.Default
    private List<ToolSpec> tools = new ArrayList<>();
}
