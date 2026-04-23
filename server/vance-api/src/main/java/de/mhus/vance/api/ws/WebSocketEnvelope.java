package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Generic envelope carried on every WebSocket frame.
 *
 * The {@code data} payload is intentionally untyped ({@code Object}) so the envelope
 * can represent every message type without type gymnastics. Senders pass the typed
 * payload POJO (e.g. {@code WelcomeData}, {@code PingData}); receivers parse the
 * envelope, inspect {@link #getType()}, and convert {@link #getData()} to the
 * expected DTO using {@code ObjectMapper.convertValue(...)}.
 *
 * Exactly one of {@link #getId()} or {@link #getReplyTo()} is set on any given
 * message — never both. See {@code specification/websocket-protokoll.md} §1 for
 * the rules.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketEnvelope {

    /** Request-id set by the sender of a request; echoed back in {@code replyTo}. */
    private @Nullable String id;

    /** Carries the {@code id} of the original request when this message is a response. */
    private @Nullable String replyTo;

    /** One of {@link MessageType}. Never null on the wire. */
    private String type = "";

    /** Typed payload; see per-type DTOs in this package. */
    private @Nullable Object data;

    public static WebSocketEnvelope request(String id, String type, @Nullable Object data) {
        WebSocketEnvelope e = new WebSocketEnvelope();
        e.id = id;
        e.type = type;
        e.data = data;
        return e;
    }

    public static WebSocketEnvelope reply(String replyTo, String type, @Nullable Object data) {
        WebSocketEnvelope e = new WebSocketEnvelope();
        e.replyTo = replyTo;
        e.type = type;
        e.data = data;
        return e;
    }

    public static WebSocketEnvelope notification(String type, @Nullable Object data) {
        WebSocketEnvelope e = new WebSocketEnvelope();
        e.type = type;
        e.data = data;
        return e;
    }
}
