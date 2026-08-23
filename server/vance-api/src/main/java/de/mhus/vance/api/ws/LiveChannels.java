package de.mhus.vance.api.ws;

/**
 * Channel names of the multi-channel Live-WS envelope ({@link LiveEnvelope}).
 *
 * <p>Introduced with the {@code clients} channel so brain and foot share one
 * constant instead of repeating the literal on both ends. The older channels
 * are listed for completeness; their existing call sites still use literals.
 */
public final class LiveChannels {

    /** Chat stream, session + process lifecycle. Wraps a {@link WebSocketEnvelope}. */
    public static final String SESSION = "session";

    /** Document presence + change push. */
    public static final String DOCUMENTS = "documents";

    /** Ephemeral live cursors per document path. */
    public static final String POINTERS = "pointers";

    /** Generic ephemeral per-document signals. */
    public static final String SIGNALS = "signals";

    /**
     * Remote control of running CLI clients — session- and project-independent
     * (the WS exists before any session is bound). See
     * {@code planning/foot-remote-control.md}.
     */
    public static final String CLIENTS = "clients";

    private LiveChannels() {
    }
}
