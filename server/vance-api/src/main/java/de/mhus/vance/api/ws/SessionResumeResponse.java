package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reply to a {@link MessageType#SESSION_RESUME} request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionResumeResponse {

    private String sessionId;

    private String projectId;

    /**
     * Name of the session's chat-process (typically {@code "chat"}),
     * if one is registered on the session. Lets the client set its
     * active-process pointer right after resume without an extra
     * round-trip — same convenience {@link SessionBootstrapResponse}
     * already provides on the bootstrap path.
     *
     * <p>{@code null} when the session has no chat-process registered
     * (rare; expected only for legacy sessions or daemon-driven flows).
     */
    private @org.jspecify.annotations.Nullable String chatProcessName;
}
