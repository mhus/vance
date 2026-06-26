package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.common.AccentColor;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * WS request payload for {@link MessageType#SESSION_METADATA_PATCH}.
 * Same semantics as the REST {@code SessionMetadataPatchRequest}:
 * every field is optional; {@code null}/absent means "do not change".
 * The bound session is identified by {@link #sessionId} — the brain
 * additionally verifies it matches the WS connection's bound session.
 *
 * <p>Reply: {@code de.mhus.vance.api.session.SessionMetadataDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionMetadataPatchWsRequest {

    private String sessionId = "";

    private @Nullable String title;

    private @Nullable String icon;

    private @Nullable AccentColor color;

    /** Pass an empty list to clear tags; omit to leave them unchanged. */
    private @Nullable List<String> tags;

    private @Nullable Boolean pinned;

    /**
     * Toggle multi-user permission on the session — owner-only.
     * See {@code planning/multi-user-sessions.md} §2.1.
     */
    private @Nullable Boolean allowMultipleClients;
}
