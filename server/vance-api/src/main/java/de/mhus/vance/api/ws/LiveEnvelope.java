package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Outer envelope of the multi-channel Live-WS protocol.
 *
 * <p>Carried on every frame of {@code /brain/{tenant}/ws/live}. The
 * {@link #channel} field demuxes between channel-specific payload semantics.
 *
 * <p>v1 only implements the {@code session} channel — wraps a regular
 * {@link WebSocketEnvelope} (the chat-frame format that used to live on
 * the bare {@code /brain/{tenant}/ws} endpoint before this multi-channel
 * envelope replaced it). Future channels ({@code documents},
 * {@code notify}, {@code progress}, {@code control}) are reserved at the
 * protocol level for forward-compatibility but not handled yet.
 *
 * <p>For {@code channel="session"}, {@link #payload} carries a regular
 * {@link WebSocketEnvelope}. {@link #sessionId} identifies the conversation
 * the frame attaches to and is mandatory on the session channel.
 *
 * <p>See {@code planning/live-ws.md} §4 for the envelope contract.
 */
@GenerateTypeScript("ws")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveEnvelope {

    /** Channel router — {@code "session"} is the only handled value in v1. */
    private String channel = "";

    /** Session identity for {@code channel="session"} frames; null otherwise. */
    private @Nullable String sessionId;

    /** Channel-specific payload. For {@code "session"}: a {@link WebSocketEnvelope}. */
    private @Nullable Object payload;
}
