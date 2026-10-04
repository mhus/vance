package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request payload for {@code process-inbox} — the not-yet-drained queue of
 * one think-process of the bound session ("active message queue", see
 * {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>The authoritative queue read: a client that reconnects (or joins a
 * multi-user session late) rebuilds its "queued" view from here instead of
 * trusting the optimistic {@code process-queue} pushes.
 *
 * <p>Address the process by {@code name} (preferred) or {@code processId},
 * mirroring {@link ProcessMessagesRequest}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessInboxRequest {

    /** Process name, unique within the session. Preferred addressing. */
    private @Nullable String name;

    /** Mongo id of the process — alternative to {@link #name}. */
    private @Nullable String processId;
}
