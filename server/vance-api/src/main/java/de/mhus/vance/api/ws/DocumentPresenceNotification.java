package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server → Client push payload on the {@code documents} channel: who is
 * currently subscribed to {@link #path}.
 *
 * <p>The server renders this list **per recipient**, leaving the
 * recipient's own {@code editorId} out — see
 * {@code planning/document-presence.md} §4.4.
 *
 * <p>Wire frame:
 *
 * <pre>{@code
 * { "channel": "documents",
 *   "payload": { "type": "presence",
 *                "data": { "path": "_vance/notes.md",
 *                          "viewers": [ {editorId, userId, displayName}, ... ] } } }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentPresenceNotification {

    /** Document path this roster applies to. */
    private String path;

    /** Viewers currently subscribed (excluding the recipient's own editorId). */
    private List<DocumentViewer> viewers;
}
