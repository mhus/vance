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
 * Reply payload for {@code process-inbox} — the queued-but-not-yet-drained
 * messages of one think-process, oldest first.
 *
 * <p>Empty {@code messages} means the engine has nothing waiting: either
 * nothing was queued, or it already picked everything up at a loop
 * boundary. See {@code planning/active-message-queue.md} §4 P3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessInboxResponse {

    private @Nullable String thinkProcessId;

    private @Nullable String processName;

    /** Queued entries in arrival order. Never {@code null} — empty when the queue is clear. */
    private @Nullable List<QueuedMessageEntry> messages;
}
