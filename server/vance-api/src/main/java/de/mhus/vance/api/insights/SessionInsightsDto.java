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

    /** Connection profile of the client that created this session ({@code FOOT}/{@code WEB}/{@code MOBILE}). */
    private String profile;

    private String clientVersion;

    /** Optional client-supplied identifier (logs / UI). */
    private @Nullable String clientName;

    private String status;

    private @Nullable String boundConnectionId;

    /** Mongo id of the auto-spawned session-chat process — links to a process inspection. */
    private @Nullable String chatProcessId;

    private @Nullable Instant createdAt;

    private @Nullable Instant lastActivityAt;

    /**
     * First USER-role chat message in the session, truncated to 250
     * characters. Set once on the very first user message — this is
     * the session's stable "topic" surfaced in pickers and lists.
     */
    private @Nullable String firstUserMessage;

    /** Most recent message preview (truncated to 250 characters). */
    private @Nullable String lastMessagePreview;

    /** Role of the message captured in {@link #lastMessagePreview}. */
    private @Nullable String lastMessageRole;

    /** When {@link #lastMessagePreview} was created. */
    private @Nullable Instant lastMessageAt;

    /** Number of think-processes attached to this session — populated for list views. */
    private @Nullable Integer processCount;
}
