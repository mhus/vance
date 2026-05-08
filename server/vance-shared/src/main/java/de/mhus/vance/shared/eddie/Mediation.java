package de.mhus.vance.shared.eddie;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.eddie.MediationState;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Eddie-only embedded record on her own {@code ThinkProcessDocument}
 * that marks an active WS-mediation: the user-client is currently
 * bound to a worker session via {@code session-rebind} rather than
 * Eddie's hub session. Eddie's LLM lane pauses; engine event-handlers
 * stay alive so the return choreography can fire.
 *
 * <p>{@code workerSessionId} is the rebind target the user-client
 * jumped to. {@code workerProcessId} is the process Eddie was
 * observing — used for plan-mirror reconciliation when the mediation
 * ends. {@code state} flips {@code ACTIVE → RETURNING} during the
 * cleanup phase; afterwards the field is set back to {@code null} on
 * Eddie's process.
 *
 * <p>See {@code specification/eddie-engine.md} §8.5.3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mediation {

    /** Worker {@code ThinkProcessDocument._id} the mediation was opened against. */
    private String workerProcessId = "";

    /** Worker session id — the rebind target. */
    private String workerSessionId = "";

    private @Nullable Instant startedAt;

    @Builder.Default
    private MediationState state = MediationState.ACTIVE;
}
