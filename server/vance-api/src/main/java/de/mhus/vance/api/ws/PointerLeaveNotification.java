package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server → Client push on the {@code pointers} channel: a participant's
 * pointer left {@link #path} (mouse-leave, tab-blur, unsubscribe, or WS
 * disconnect). Receivers remove the pointer identified by {@link #editorId}
 * from their overlay.
 *
 * <p>Belt-and-suspenders: a client-side TTL fade is the robust primary
 * cleanup, so a lost {@code pointer-leave} (cross-pod gap, crash) still
 * clears the stale pointer within a few seconds.
 *
 * <pre>{@code
 * { "channel": "pointers",
 *   "payload": { "type": "pointer-leave",
 *                "data": { "path": "canvas/board.canvas.yaml",
 *                          "editorId": "…" } } }
 * }</pre>
 *
 * <p>See {@code planning/pointers-channel.md} §6.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class PointerLeaveNotification {

    /** Document path the pointer left. */
    private String path;

    /** Per-connection identifier of the participant whose pointer to drop. */
    private String editorId;
}
