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

    /** {@code X-Vance-Profile} — connection profile (foot/web/mobile/daemon). */
    public static final String PROFILE = "X-Vance-Profile";

    /** {@code X-Vance-Client-Version} — SemVer of the client build. */
    public static final String CLIENT_VERSION = "X-Vance-Client-Version";

    /**
     * {@code X-Vance-Client-Name} — optional human-readable client identifier
     * (logs / UI). For {@code DAEMON} connections the brain uses this name to
     * route tool calls to the right daemon instance; for other profiles it is
     * purely informational.
     */
    public static final String CLIENT_NAME = "X-Vance-Client-Name";

    /**
     * {@code X-Vance-Client-Context} — optional JSON-encoded
     * {@link ClientContext} describing the client's platform (os / arch /
     * shell / cwd / sandbox state). Sent by CLI clients ({@code vance-foot})
     * so the brain can tell the LLM which command dialect its client-side
     * exec calls run on. Browsers cannot set custom headers and do not send
     * it — they never drive {@code client_exec_run}. A malformed value is
     * ignored, never fatal.
     */
    public static final String CLIENT_CONTEXT = "X-Vance-Client-Context";

    /**
     * Query-parameter fallback for {@link #PROFILE}. Browsers cannot
     * attach custom headers to the WebSocket upgrade, so web clients
     * pass the profile as {@code ?profile=web}.
     */
    public static final String PROFILE_PARAM = "profile";

    /**
     * Query-parameter fallback for {@link #CLIENT_VERSION}. Same reason
     * as {@link #PROFILE_PARAM}.
     */
    public static final String CLIENT_VERSION_PARAM = "clientVersion";

    /** Query-parameter fallback for {@link #CLIENT_NAME}. */
    public static final String CLIENT_NAME_PARAM = "name";

    private HandshakeHeaders() {
    }
}
