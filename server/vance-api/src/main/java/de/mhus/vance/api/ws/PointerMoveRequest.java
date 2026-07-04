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
 * Client → Brain payload on the {@code pointers} channel: "my pointer is
 * now here".
 *
 * <p>The coordinates {@link #x} / {@link #y} are <b>opaque application
 * space</b> — the framework never interprets, clamps or transforms them.
 * Zoom / pan / canvas-transform is entirely the application's concern; a
 * Canvas client sends coordinates already mapped into its own graph space.
 *
 * <p>The sender does <b>not</b> include its own identity — the brain
 * derives {@code editorId} / {@code userId} / {@code displayName} from the
 * authenticated {@link de.mhus.vance.brain.ws.ConnectionContext} and puts
 * them on the outgoing {@link PointerNotification}. That keeps the label
 * unspoofable.
 *
 * <pre>{@code
 * { "channel": "pointers",
 *   "payload": { "type": "pointer-move",
 *                "data": { "path": "canvas/board.canvas.yaml",
 *                          "x": 412.5, "y": 88.0,
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
public class PointerMoveRequest {

    /** Document path the pointer belongs to. Required. */
    private String path;

    /** Opaque application-space X coordinate. */
    private double x;

    /** Opaque application-space Y coordinate. */
    private double y;

    /**
     * Optional application-defined extras (e.g. pointer color, pointer
     * type, small selection hint). Passed through verbatim; the framework
     * never reads it.
     */
    private @Nullable Map<String, Object> data;
}
