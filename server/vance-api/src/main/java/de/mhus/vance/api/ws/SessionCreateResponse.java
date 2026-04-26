package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to a {@link MessageType#SESSION_CREATE} request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionCreateResponse {

    private String sessionId;

    private String projectId;

    /**
     * Mongo id of the auto-spawned session-chat think-process —
     * the orchestrator the client routes default chat input to.
     * {@code null} only when the chat-engine is missing or
     * misconfigured (the session is still usable, but
     * client-side default-target routing has nothing to point at).
     */
    private @Nullable String chatProcessId;

    /** Process name behind {@link #chatProcessId} — the value the
     *  client uses for {@code process-steer.processName}. */
    private @Nullable String chatProcessName;

    /** Engine name behind {@link #chatProcessId}, e.g. {@code "arthur"}. */
    private @Nullable String chatEngine;
}
