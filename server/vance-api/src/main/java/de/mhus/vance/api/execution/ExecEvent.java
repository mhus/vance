package de.mhus.vance.api.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Foot → Brain push: a single shell-job life-cycle event.
 *
 * <ul>
 *   <li>{@link Kind#STARTED} — sent right after spawn. Carries command,
 *       session/project bind (if any), startedAt, log paths.</li>
 *   <li>{@link Kind#TICK} — throttled output-progress signal so the
 *       brain registry sees a fresh {@code lastOutputAt}. Carries id +
 *       lastOutputAt.</li>
 *   <li>{@link Kind#ENDED} — sent on terminal transition. Carries id,
 *       status, exitCode, endedAt.</li>
 * </ul>
 *
 * <p>Brain enriches incoming events with {@code tenantId} from the
 * connection context — foot does not know its tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("execution")
public class ExecEvent {

    public enum Kind { STARTED, TICK, ENDED }

    /**
     * One of {@link Kind} values, transported as a string so generated
     * TypeScript stays simple. Use {@link #kindOrNull()} for typed access.
     */
    private String kind;

    private String executionId;

    public void setKindEnum(Kind k) {
        this.kind = k == null ? null : k.name();
    }

    public @Nullable Kind kindOrNull() {
        if (kind == null) return null;
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Set on STARTED. */
    private @Nullable String command;

    /** Set on STARTED, when the foot is bound to a session. */
    private @Nullable String sessionId;

    /** Set on STARTED, when the foot is bound to a session. */
    private @Nullable String projectId;

    /** Set on STARTED. */
    private @Nullable Instant startedAt;

    /** Updated on every kind. */
    private @Nullable Instant lastOutputAt;

    /** Set on ENDED. */
    private @Nullable Instant endedAt;

    /** Set on ENDED. One of RUNNING / COMPLETED / FAILED / KILLED / ORPHANED. */
    private @Nullable String status;

    /** Set on ENDED, when the process produced an exit code. */
    private @Nullable Integer exitCode;

    /** Set on STARTED. */
    private @Nullable String stdoutPath;

    /** Set on STARTED. */
    private @Nullable String stderrPath;

    /**
     * Set on ENDED to {@code true} when the foot-side watchdog killed
     * the job because its {@code deadlineSeconds} elapsed (as opposed
     * to natural completion or an explicit {@code client_exec_kill}).
     * Lets the brain emit {@code EXEC_TIMEOUT} instead of
     * {@code EXEC_FINISHED} when pushing the event into the
     * owner-process inbox.
     */
    private @Nullable Boolean timedOut;
}
