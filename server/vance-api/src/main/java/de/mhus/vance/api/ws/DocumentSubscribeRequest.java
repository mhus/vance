package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client → Brain payload for {@code documents}-channel
 * {@code subscribe} and {@code unsubscribe} frames.
 *
 * <p>Carries the document path the client wants to be informed about.
 * The wire frame puts this in the {@code data} field of the inner
 * {@link WebSocketEnvelope} that sits as {@code payload} of the outer
 * {@link LiveEnvelope}:
 *
 * <pre>{@code
 * { "channel": "documents",
 *   "payload": { "type": "subscribe",
 *                "data": { "path": "_vance/notes.md" } } }
 * }</pre>
 *
 * <p>See {@code planning/document-presence.md} §4.1 + §4.2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentSubscribeRequest {

    /** Document path (e.g. {@code _vance/notes.md}). Required. */
    private String path;
}
