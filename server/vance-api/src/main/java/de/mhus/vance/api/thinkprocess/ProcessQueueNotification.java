package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Server-initiated {@code process-queue} notification — the queue view of
 * one think-process changed (see {@code planning/active-message-queue.md}
 * §4 P3).
 *
 * <p>Two change kinds share the frame and either list may be empty:
 *
 * <ul>
 *   <li><b>{@code added}</b> — messages were appended to the queue. The
 *       sending client already knows its own sends (persist-ack carries
 *       the {@code messageId}); the push exists for the multi-client view
 *       and for clients that reconnect later.</li>
 *   <li><b>{@code drainedIds}</b> — the engine picked these up at a loop
 *       boundary ("picked up"), which is the client's signal to drop the
 *       queued badge.</li>
 * </ul>
 *
 * <p>Optimistic side-channel: no client connected → dropped on the floor.
 * The authoritative queue state is the {@code process-inbox} read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessQueueNotification {

    private @Nullable String thinkProcessId;

    private @Nullable String processName;

    /** Entries appended since the last frame. */
    private @Nullable List<QueuedMessageEntry> added;

    /** {@code messageId}s the engine has drained (picked up). */
    private @Nullable List<String> drainedIds;
}
