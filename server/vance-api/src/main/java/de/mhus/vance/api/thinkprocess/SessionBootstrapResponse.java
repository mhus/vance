package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous reply to {@code session-bootstrap}.
 *
 * <p>The actual chat content (greetings, {@code initialMessage} response)
 * arrives in parallel as {@code chat-message-appended} notifications — same
 * contract as the individual {@code process-create} / {@code process-steer}
 * commands.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class SessionBootstrapResponse {

    private String sessionId;

    private String projectId;

    /** {@code true} if the session was created by this call. */
    private boolean sessionCreated;

    /** Processes that this call newly created. */
    @Builder.Default
    private List<BootstrappedProcess> processesCreated = new ArrayList<>();

    /**
     * Processes that already existed under the same name — left untouched.
     * Empty list when creating a fresh session.
     */
    @Builder.Default
    private List<BootstrappedProcess> processesSkipped = new ArrayList<>();

    /** Name of the process {@code initialMessage} was steered to, if any. */
    private @Nullable String steeredProcessName;
}
