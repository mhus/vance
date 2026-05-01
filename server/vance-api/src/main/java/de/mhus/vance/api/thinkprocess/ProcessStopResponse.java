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
 * Reply for {@code process-stop}.
 *
 * <p>For the named-target form, {@link #stoppedProcessNames} contains
 * the single name and {@link #status} / {@link #closeReason} reflect
 * the final state. For the broadcast form (no name in the request),
 * {@code stoppedProcessNames} lists all the processes that were
 * stopped and the per-process state fields are null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessStopResponse {

    /** Names of processes that were stopped (may be empty). */
    private List<String> stoppedProcessNames;

    /** Final status — only set for the named-target form. */
    private @Nullable ThinkProcessStatus status;

    /** Set only when {@link #status} is CLOSED. */
    private @Nullable CloseReason closeReason;
}
