package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.tools.ToolSpec;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Live snapshot of the client-side tools a session has pushed to the
 * server. Lives only on the pod that owns the session's WebSocket bind;
 * Layer 1 forwards the request to that pod via the workspace-routing
 * cache (project pod = session pod).
 *
 * <p>{@link #bound} is {@code false} when the registry holds no entry
 * for the session — either no client is currently connected or the
 * session never registered any tools. The field exists so the UI can
 * differentiate "client connected but offered no tools" (bound, empty
 * list) from "no client connected" (not bound).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class SessionClientToolsDto {

    private String sessionId;

    /** {@code true} when the registry currently holds an entry for the session. */
    private boolean bound;

    /** WebSocket connection id the client used at registration time. */
    private @Nullable String connectionId;

    /** Tool specs the client pushed at register-time, in registration order. */
    private List<ToolSpec> tools;
}
