package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of a {@link MessageType#CLIENT_AGENT_UPLOAD} envelope.
 *
 * <p>Foot reads its local {@code ./agent.md} after session-bind and
 * pushes the full text up. The brain stores it on the session and
 * — if the active recipe's profile-block opts in — splices it into
 * the conversation's memory block.
 *
 * <p>{@link #path} is informational (used in the prompt heading so
 * the LLM sees where the doc came from); {@link #content} is the
 * raw file body. Soft size cap of 64 KB enforced both client- and
 * server-side; oversize uploads are rejected with HTTP-style 413.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ClientAgentUploadRequest {

    /** Display path / filename for the prompt heading, e.g. {@code "./agent.md"}. */
    private @Nullable String path;

    /** Raw Markdown body. Empty string clears any previously uploaded doc. */
    private String content;
}
