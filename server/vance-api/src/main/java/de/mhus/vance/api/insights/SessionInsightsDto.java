package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only summary of a session for the insights inspector.
 *
 * <p>{@link #status} is the {@code SessionStatus} value as a string
 * ({@code OPEN} / {@code CLOSED}) — wire-stable without pulling
 * {@code SessionStatus} from {@code vance-shared} into the API
 * contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class SessionInsightsDto {

    /** Mongo id — used as the addressing key for the inspector. */
    private String id;

    /** Business id ({@code sess_...}). */
    private String sessionId;

    private String userId;

    private String projectId;

    private @Nullable String displayName;

    private String clientType;

    private String clientVersion;

    private String status;

    private @Nullable String boundConnectionId;

    /** Mongo id of the auto-spawned session-chat process — links to a process inspection. */
    private @Nullable String chatProcessId;

    private @Nullable Instant createdAt;

    private @Nullable Instant lastActivityAt;

    /** Number of think-processes attached to this session — populated for list views. */
    private @Nullable Integer processCount;
}
