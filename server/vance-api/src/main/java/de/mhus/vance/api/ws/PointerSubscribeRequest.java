package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client → Brain payload for {@code pointers}-channel
 * {@code subscribe} and {@code unsubscribe} frames.
 *
 * <p>Carries the document path whose live-pointer stream the client wants
 * to send to and receive from. Same path model as the {@code documents}
 * channel, but the {@code pointers} channel keeps <b>no</b> presence
 * roster — it is a pure ephemeral fan-out.
 *
 * <pre>{@code
 * { "channel": "pointers",
 *   "payload": { "type": "subscribe",
 *                "data": { "path": "canvas/board.canvas.yaml" } } }
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
public class PointerSubscribeRequest {

    /** Document path the pointer stream is scoped to. Required. */
    private String path;
}
