package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.cli.VanceCliConfig;
import de.mhus.vance.cli.chat.ConnectionManager;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Capabilities a {@link Command} can use. Kept as an interface so commands do
 * not depend on the concrete {@code ChatCommand} orchestrator and can be tested
 * with a plain fake.
 */
public interface CommandContext {

    /** Info line in the chat history (grey, leading "·"). */
    void info(String text);

    /** System line (cyan, leading "*"). For lifecycle events. */
    void system(String text);

    /** Error line (red, leading "!!"). */
    void error(String text);

    /** Outbound-message line (green, leading "→"). */
    void sent(String text);

    /** Inbound-message line (white, leading "←"). For typed, pretty-printed replies. */
    void received(String text);

    ConnectionManager connection();

    CommandRegistry registry();

    VanceCliConfig config();

    /** Wipes the history panel. */
    void clearHistory();

    /** Request a clean exit of the TUI. Must be safe to call repeatedly. */
    void quit();

    /**
     * Register a handler that will be called when a reply envelope with
     * {@code replyTo == requestId} arrives. The router removes the handler as
     * soon as it fires or its timeout expires, so there is no need to
     * unregister manually. On timeout the user sees a generic
     * "request timed out" line — override by passing a smaller timeout.
     */
    void expectReply(String requestId, Consumer<WebSocketEnvelope> handler);

    /**
     * Same as {@link #expectReply(String, Consumer)} but with a custom timeout.
     */
    void expectReply(String requestId, Consumer<WebSocketEnvelope> handler, long timeoutMs);

    /**
     * Shared Jackson mapper, usable by command reply-handlers to convert
     * {@link WebSocketEnvelope#getData()} (an opaque {@code Object} on the wire,
     * typically a {@code LinkedHashMap}) to a typed DTO via
     * {@link ObjectMapper#convertValue(Object, Class)}.
     */
    ObjectMapper mapper();

    /**
     * Convenience helper equivalent to {@code mapper().convertValue(data, type)}
     * with a null-guard. Returns {@code null} if {@code data} is {@code null}.
     */
    default <T> @Nullable T parseData(@Nullable Object data, Class<T> type) {
        if (data == null) {
            return null;
        }
        return mapper().convertValue(data, type);
    }
}
