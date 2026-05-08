package de.mhus.vance.foot.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root configuration for the Foot CLI. Bound to the {@code vance} prefix in
 * {@code application.yaml}. Mutable POJO so Spring's relaxed binding can fill
 * fields directly.
 */
@Data
@ConfigurationProperties(prefix = "vance")
public class FootConfig {

    private Brain brain = new Brain();
    private Auth auth = new Auth();
    private Client client = new Client();
    private Debug debug = new Debug();
    private History history = new History();
    private Bootstrap bootstrap = new Bootstrap();
    private Ui ui = new Ui();
    private Ide ide = new Ide();

    @Data
    public static class Brain {
        private String httpBase = "http://localhost:8080";
        private String wsBase = "ws://localhost:8080";
    }

    @Data
    public static class Auth {
        private String tenant = "acme";
        private String username = "wile.coyote";
        private String password = "acme-rocket";
    }

    @Data
    public static class Client {
        private String version = "0.1.0";
        /**
         * Optional human-readable client identifier sent during the WebSocket
         * handshake. Surfaced in brain logs and the session inspector — useful
         * when running multiple foot instances against the same brain.
         */
        private @Nullable String name;
    }

    @Data
    public static class Debug {
        private Rest rest = new Rest();
    }

    @Data
    public static class Rest {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 8766;
    }

    /**
     * Persistent input-history file — the list of lines the user has
     * submitted, the same set that ARROW_UP / ARROW_DOWN walks. Plain text,
     * one submitted line per file line (the {@code .bash_history} shape).
     *
     * <p>Default path when {@link #file} is {@code null}: {@code ~/.vance/foot-history}.
     * A leading {@code ~/} in {@link #file} is expanded against {@code user.home}.
     */
    @Data
    public static class History {
        private boolean enabled = true;
        private @Nullable String file;
        private int maxEntries = 500;
    }

    /**
     * Auto-bootstrap payload. Empty = no bootstrap fired on welcome.
     * If {@link #processes} is non-empty (and either {@code projectId} or
     * {@code sessionId} is set), {@code AutoBootstrapService} sends a
     * {@code session-bootstrap} after the welcome frame.
     */
    @Data
    public static class Bootstrap {
        /** Required when {@link #sessionId} is null — projectId for a new session. */
        private @Nullable String projectId;
        /** If set, resume this session instead of creating a new one. */
        private @Nullable String sessionId;
        private List<BootstrapProcess> processes = new ArrayList<>();
        /** Optional first chat message steered to the first process. */
        private @Nullable String initialMessage;
    }

    /**
     * Terminal output appearance. {@link #lineMaxChars} caps grey
     * info/verbose/debug lines and green worker lines (sub-process chat
     * echoes), so a long worker reply doesn't drown out the main chat.
     * Set to {@code 0} to disable truncation.
     *
     * <p>{@link #colors} lets the user override the per-channel ANSI
     * style. Each value follows JLine's style-expression syntax:
     * comma-separated tokens, e.g. {@code fg:red,bold} or
     * {@code fg:bright-black,italic}. Empty / blank value means "no
     * styling" (terminal default — typically white). See
     * {@link de.mhus.vance.foot.ui.StyleParser} for the full token
     * grammar.
     */
    @Data
    public static class Ui {
        private int lineMaxChars = 140;
        private Colors colors = new Colors();
        private StatusBar statusBar = new StatusBar();
    }

    /**
     * Controls the pinned status line at the bottom of the JLine REPL.
     * The status block always ends with one blank trailing row so the
     * cursor never sits on the same physical line as the spinner; that
     * stops some terminals from scrolling each repaint into the buffer.
     */
    @Data
    public static class StatusBar {
        private boolean enabled = true;
        private boolean animated = true;
    }

    /**
     * Per-channel style overrides. Defaults match the original built-in
     * palette (grey side-channel, green worker, yellow warn, red error,
     * white/default for the main chat reply).
     */
    @Data
    public static class Colors {
        private String chat = "";
        private String worker = "fg:green";
        private String info = "fg:bright-black";
        private String verbose = "fg:bright-black";
        private String debug = "fg:bright-black";
        private String warn = "fg:yellow";
        private String error = "fg:red";
    }

    /**
     * IDE-bridge configuration. Disabled by default — the {@code chat}
     * subcommand turns Claude on with {@code --intellij-claude}
     * (planning/foot-ide-bridge.md §10).
     */
    @Data
    public static class Ide {
        private Claude claude = new Claude();
    }

    @Data
    public static class Claude {
        private boolean enabled = false;
    }

    @Data
    public static class BootstrapProcess {
        /**
         * Engine name (e.g. {@code "ford"}) — one of {@code engine}
         * or {@link #recipe} must be set. If both, {@code recipe}
         * wins.
         */
        private String engine = "";

        /** Recipe name for the recipe cascade. Preferred over
         *  {@link #engine}. */
        private @Nullable String recipe;

        private String name = "";
        private @Nullable String title;
        private @Nullable String goal;

        /**
         * Engine-specific runtime parameters — see
         * {@code de.mhus.vance.api.thinkprocess.ProcessSpec#getParams()}.
         * Empty map = engine defaults.
         */
        private Map<String, Object> params = new LinkedHashMap<>();
    }
}


