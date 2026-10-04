package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous ack for {@code process-steer}. Since the active message
 * queue ({@code planning/active-message-queue.md} §2) this ack is
 * <b>persist-bound</b>: it confirms the message is durably queued, not that
 * the engine has finished working on it. The assistant reply arrives as one
 * or more {@code chat-message-appended} notifications, turn completion as a
 * {@code process-progress} event ({@code ENGINE_TURN_END}), and the queue
 * uptake as a {@code process-queue} notification ({@code drainedIds}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSteerResponse {

    private String thinkProcessId;

    private String processName;

    private ThinkProcessStatus status;

    /** Id of the queued message — the key clients track for the "queued" display. */
    private @Nullable String messageId;

    /** Queue depth after this enqueue (count of not-yet-drained messages). */
    private @Nullable Integer queueDepth;
}
