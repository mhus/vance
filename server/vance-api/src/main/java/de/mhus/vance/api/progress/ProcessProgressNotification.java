package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code process-progress} server notification — the
 * unified envelope for all live status updates from a running
 * think-process.
 *
 * <p>Carries one of three payload variants discriminated by {@link #kind}:
 * cumulative metrics, structured plan snapshot, or free-form status ping.
 * Exactly one of {@link #metrics}, {@link #plan}, {@link #status} is set
 * per message; the other two stay {@code null}.
 *
 * <p>The source block ({@link #processId}, {@link #processName},
 * {@link #processTitle}, {@link #engine}, {@link #sessionId},
 * {@link #parentProcessId}) is always populated so the client can render
 * without an extra REST round-trip.
 *
 * <p>Side-channel only — these messages do <strong>not</strong> enter
 * conversation history, are not persisted server-side, and do not feed
 * back into the LLM context. See {@code specification/user-progress-channel.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class ProcessProgressNotification {

    private String processId;

    private String processName;

    private @Nullable String processTitle;

    private String engine;

    private String sessionId;

    private @Nullable String parentProcessId;

    private ProgressKind kind;

    private @Nullable MetricsPayload metrics;

    private @Nullable PlanPayload plan;

    private @Nullable StatusPayload status;

    private @Nullable Instant emittedAt;
}
