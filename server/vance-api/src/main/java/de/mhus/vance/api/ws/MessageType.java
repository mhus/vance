package de.mhus.vance.api.ws;

/**
 * Canonical {@code type} values for the {@link WebSocketEnvelope}.
 *
 * See {@code specification/websocket-protokoll.md} §6 for the catalog.
 */
public final class MessageType {

    public static final String WELCOME = "welcome";
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final String LOGOUT = "logout";
    public static final String ERROR = "error";

    private MessageType() {
    }
}
