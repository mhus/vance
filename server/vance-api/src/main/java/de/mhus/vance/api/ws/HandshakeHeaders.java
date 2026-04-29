package de.mhus.vance.api.ws;

/**
 * HTTP header names exchanged during the WebSocket handshake.
 *
 * See {@code specification/websocket-protokoll.md} §2 for the full handshake contract.
 */
public final class HandshakeHeaders {

    /** {@code Authorization: Bearer &lt;jwt&gt;} — carries the JWT used for authentication. */
    public static final String AUTHORIZATION = "Authorization";

    /** Wire prefix of the {@link #AUTHORIZATION} header value. */
    public static final String BEARER_PREFIX = "Bearer ";

    /** {@code X-Vance-Client-Type} — client kind (cli/web/desktop/mobile). */
    public static final String CLIENT_TYPE = "X-Vance-Client-Type";

    /** {@code X-Vance-Client-Version} — SemVer of the client build. */
    public static final String CLIENT_VERSION = "X-Vance-Client-Version";

    /**
     * Query-parameter fallback for {@link #CLIENT_TYPE}. Browsers cannot
     * attach custom headers to the WebSocket upgrade, so web clients
     * pass the type as {@code ?clientType=web}.
     */
    public static final String CLIENT_TYPE_PARAM = "clientType";

    /**
     * Query-parameter fallback for {@link #CLIENT_VERSION}. Same reason
     * as {@link #CLIENT_TYPE_PARAM}.
     */
    public static final String CLIENT_VERSION_PARAM = "clientVersion";

    private HandshakeHeaders() {
    }
}
