package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Server → Client push on the {@code pointers} channel: another
 * participant's pointer moved on {@link #path}.
 *
 * <p>Ephemeral, best-effort, fire-and-forget — no roster, no history, not
 * replayed on reconnect. The frame for the sender's own connection is
 * filtered out server-side (via {@link #editorId}), so a client never sees
 * its own pointer echoed back.
 *
 * <p>Coordinates {@link #x} / {@link #y} are opaque application space (see
 * {@link PointerMoveRequest}); the receiving application applies its own
 * transform to position the pointer overlay.
 *
 * <pre>{@code
 * { "channel": "pointers",
 *   "payload": { "type": "pointer",
 *                "data": { "path": "canvas/board.canvas.yaml",
 *                          "editorId": "…", "userId": "alice",
 *                          "displayName": "Alice", "x": 412.5, "y": 88.0,
 *                          "data": { "color": "#e11" } } } }
 * }</pre>
 *
 * <p>See {@code planning/pointers-channel.md} §4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class PointerNotification {

    /** Document path this pointer applies to. */
    private String path;

    /** Server-assigned per-connection identifier of the sender. */
    private String editorId;

    /** JWT-derived user identity of the sender. */
    private String userId;

    /** Human-readable display name (falls back to {@link #userId} server-side). */
    private String displayName;

    /** Opaque application-space X coordinate. */
    private double x;

    /** Opaque application-space Y coordinate. */
    private double y;

    /** Optional application-defined extras, passed through verbatim. */
    private @Nullable Map<String, Object> data;
}
