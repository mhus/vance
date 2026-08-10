package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request payload for {@code process-messages} — the conversation of one
 * think-process of the bound session, for a client's process detail view.
 *
 * <p>Address the process by {@code name} (stable, what the user sees in
 * {@code process-list}) or by {@code processId}; exactly one is needed and
 * {@code name} wins when both are set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessMessagesRequest {

    /** Process name, unique within the session. Preferred addressing. */
    private @Nullable String name;

    /** Mongo id of the process — alternative to {@link #name}. */
    private @Nullable String processId;

    /**
     * Newest-N cap. {@code null} or {@code <= 0} means the server default
     * (200). The server cuts at the head, keeping the most recent turns.
     */
    private @Nullable Integer limit;
}
