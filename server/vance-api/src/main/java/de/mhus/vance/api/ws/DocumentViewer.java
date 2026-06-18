package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One viewer entry inside a {@link DocumentPresenceNotification}.
 *
 * <p>Primary key is {@link #editorId} — one open WebSocket = one
 * {@code editorId}. The same {@link #userId} can appear multiple times in
 * the viewer list if the user has the document open in multiple tabs.
 *
 * <p>The server pre-filters each recipient's own {@code editorId} out of
 * the list it broadcasts — see {@code planning/document-presence.md} §4.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentViewer {

    /** Server-assigned per-connection identifier. Unique within the cluster. */
    private String editorId;

    /** JWT-derived user identity of the viewer. */
    private String userId;

    /** Human-readable display name (falls back to {@link #userId} server-side). */
    private String displayName;
}
