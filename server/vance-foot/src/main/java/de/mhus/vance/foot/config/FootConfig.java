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
         * Human-readable client identifier sent during the WebSocket
         * handshake. Always sent; falls back to {@code vance.auth.username}
         * when null. Surfaced in brain logs and the session inspector —
         * useful when running multiple foot instances against the same
         * brain. Override with {@code --name=<value>}.
         */
        private @Nullable String name;
        /**
         * WebSocket profile (capability bundle) the foot announces on
         * connect. {@code "foot"} (default) gets shell + FS tools +
         * client-side {@code agent.md}. {@code "daemon"} for headless
         * tool-providers. {@code "web"} for browser-style minimal-perm
         * clients. {@code "mobile"} for mobile apps. Custom tenant
         * profiles allowed (see {@code Profiles.PATTERN}). Override with
         * {@code --profile=<name>}.
         */
        private String profile = "foot";
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
        /**
         * On {@code --resume}: how many recent chat messages to replay
         * into the scrollback after binding to the picked session.
         * Set to {@code 0} to disable replay. Bumped via
         * {@code vance.bootstrap.replay-messages}.
         */
        private int replayMessages = 5;
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
        private WindowTitle windowTitle = new WindowTitle();
    }

    /**
     * Surrounding terminal's tab/window title via OSC 0 escape. On by
     * default; disable for terminals that render the escape verbatim
     * instead of consuming it (rare, but configurable). Auto-suppressed
     * when stdout is not a TTY, so daemon log files never get titles.
     *
     * <p>{@link #format} is a string template expanded on every title
     * change. Available placeholders:
     * <ul>
     *   <li>{@code {glyph}} — busy/idle status glyph (𝑣 idle, ● / ○ while busy).</li>
     *   <li>{@code {session}} — current session label (blank when no session is bound).</li>
     *   <li>{@code {connection}} — connection lifecycle / tenant (blank when nothing is set).</li>
     *   <li>{@code {ide}} — {@code [ide]} when the IntelliJ bridge is attached, else blank.</li>
     * </ul>
     * Empty placeholders expand to the empty string and trailing whitespace
     * is trimmed, so a format like {@code "{glyph} {session}"} renders as
     * just {@code 𝑣} when no session is bound.
     */
    @Data
    public static class WindowTitle {
        private boolean enabled = true;
        private String format = "{glyph} {session}";
    }

    /**
     * Controls the pinned status block at the bottom of the JLine REPL.
     *
     * <p>The renderer is a bespoke ANSI painter (no JLine {@code Status})
     * that manages DECSTBM scroll region + manual cursor save/restore.
     * Works in IntelliJ's built-in terminal as well as xterm, iTerm2,
     * Terminal.app, kitty, ghostty. See
     * {@code readme/foot-status-bar-rendering.md} for the design.
     *
     * <p>{@link #bottomPadding} reserves additional empty rows below
     * the two status lines as a safety margin against bottom-row
     * auto-scroll triggers in some terminals. A minimum of 1 row is
     * always enforced; setting it higher gives the user more breathing
     * room between the prompt and the status block.
     */
    @Data
    public static class StatusBar {
        private boolean enabled = true;
        private boolean animated = true;
        private int bottomPadding = 1;
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
        private IntellijMcp intellijMcp = new IntellijMcp();
    }

    @Data
    public static class Claude {
        private boolean enabled = false;
    }

    /**
     * JetBrains IntelliJ MCP-Server bridge. When {@link #url} is set the
     * foot announces the endpoint to the brain after welcome via
     * {@code intellij-mcp-register}; the brain then upserts a
     * {@code mcp_server} ServerToolDocument and exposes the IntelliJ
     * tools (run/debug/refactor/build/database/…) to the active recipe.
     */
    @Data
    public static class IntellijMcp {
        /**
         * Streamable-HTTP MCP endpoint. {@code null}/empty disables auto-register.
         * Default for {@code --intellij-mcp-default} is set in {@code ChatRunCommand}.
         */
        private @Nullable String url;
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


