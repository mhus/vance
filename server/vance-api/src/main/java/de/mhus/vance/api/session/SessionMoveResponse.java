package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a session-move call: the session's new project plus what the
 * cleanup dropped in the source project. The caller (Web UI list menu)
 * reloads its session list to surface the move.
 *
 * <p>See {@code planning/session-move.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionMoveResponse {

    /** Business id ({@code sess_...}) of the moved session (unchanged by the move). */
    private String sessionId = "";

    /** Project the session was moved out of. */
    private String fromProjectId = "";

    /** Project the session now lives in. */
    private String toProjectId = "";

    /** Number of think-processes retargeted to the new project. */
    private int processesRetargeted;

    /** Session-/process-scoped memories deleted from the source project. */
    private long memoriesDeleted;

    /** Session-group memberships cleared in the source project. */
    private long groupsCleared;
}
