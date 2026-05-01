package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code process-pause}.
 *
 * <p>If {@link #processName} is {@code null} or blank, the brain
 * pauses <em>all non-CLOSED children of the session's chat-process</em>
 * — the typical "user pressed ESC, wants to halt activity and
 * redirect" flow. The chat-process itself stays untouched.
 *
 * <p>If {@code processName} is set, only that single process is paused.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessPauseRequest {

    /** Optional — null/blank pauses active workers. */
    private @Nullable String processName;
}
