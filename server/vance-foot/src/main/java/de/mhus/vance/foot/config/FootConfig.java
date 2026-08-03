package de.mhus.vance.foot.config;

import java.time.Duration;
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
    private Build build = new Build();
    private Auth auth = new Auth();
    private Client client = new Client();
    private Debug debug = new Debug();
    private History history = new History();
    private Bootstrap bootstrap = new Bootstrap();
    private Ui ui = new Ui();
    private Ide ide = new Ide();
    private SleepGuard sleepGuard = new SleepGuard();
    private ConversationCapture conversationCapture = new ConversationCapture();

    @Data
    public static class Brain {
        private String httpBase = "http://localhost:8080";
        private String wsBase = "ws://localhost:8080";
        /**
         * Permit plaintext ({@code http://}/{@code ws://}) transport to a
         * NON-loopback brain. Default {@code false}: plaintext is only
         * allowed to loopback (local dev works out of the box), a remote
         * plaintext base is rejected at connect. Set {@code true} in a
         * local dev config to talk to a non-loopback brain without TLS
         * (e.g. a LAN/cluster dev host) — production stays secure by
         * default (code-review Phase 2, foot-core plaintext HIGH).
         */
        private boolean allowInsecureTransport = false;

        /** Automatic re-dial after an unexpected transport drop. */
        private Reconnect reconnect = new Reconnect();
    }

    /**
     * Automatic re-dial after an <em>unexpected</em> transport drop — an
     * idle-timeout middlebox tearing down a quiet WebSocket, a network blip,
     * or a half-open socket surfaced by a keep-alive ping timeout. A
     * user-initiated {@code /disconnect} (or {@code @PreDestroy} shutdown)
     * never triggers it. Backoff grows geometrically from {@link #initialDelay}
     * up to {@link #maxDelay}.
     */
    @Data
    public static class Reconnect {
        /** Master switch. When {@code false}, a dropped connection stays down until a manual {@code /connect}. */
        private boolean enabled = true;
        /** Delay before the first re-dial attempt. */
        private java.time.Duration initialDelay = java.time.Duration.ofSeconds(1);
        /** Ceiling for the exponential backoff between attempts. */
        private java.time.Duration maxDelay = java.time.Duration.ofSeconds(30);
        /** Backoff multiplier applied to the delay after each failed attempt. */
        private double backoffMultiplier = 2.0;
        /** Max consecutive failed attempts before giving up; {@code 0} = retry forever. */
        private int maxAttempts = 0;
    }

    /**
     * Build stamp injected from the Maven reactor via resource filtering
     * ({@code vance.build.*} in {@code application.yaml}). Drives {@code
     * --version}. Defaults keep the CLI working when launched from an
     * unfiltered classpath (raw IDE resources).
     */
    @Data
    public static class Build {
        /** Maven project version, e.g. {@code 1.0.0-SNAPSHOT}. */
        private String version = "dev";
        /** Build timestamp, ISO-8601 UTC. Empty when unknown. */
        private String time = "";
    }

    @Data
    public static class Auth {
        // No baked-in credentials: these are empty by default so a bare launch
        // has no dev identity. The stored binding (.vancetope/project.eddie.yaml,
        // overlaid by ProjectBindingApplier) or an explicit config file fills
        // them; /login prompts prefill from whatever ends up here, else blank.
        private String tenant = "";
        private String username = "";
        private String password = "";
    }

    @Data
    public static class Client {
        private String version = "dev";
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
     * <p>Default path when {@link #file} is {@code null}: {@code ~/.vancetope/foot-history}.
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
        /**
         * Optional recipe override for the session-chat process — see
         * {@code SessionBootstrapRequest.chatRecipe}. Applies only on
         * session create; ignored on resume.
         */
        private @Nullable String chatRecipe;
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
        private ToolOutput toolOutput = new ToolOutput();
        private Markdown markdown = new Markdown();
        /**
         * Render the model's reasoning ("thoughts") for assistant turns
         * as a dimmed block alongside the answer. On by default; set
         * {@code false} to hide the reasoning and show only the reply.
         */
        private boolean showThoughts = true;
    }

    /**
     * Lite-Markdown-Renderer for assistant chat replies. When
     * {@link #enabled} the terminal interprets headings (colour + blank
     * lines around), tables ({@code | a | b |}-style → aligned ASCII
     * grid), code fences, blockquotes and the inline markers
     * {@code **bold**}, {@code *italic* / _italic_}, {@code `code`}.
     * Disable via {@code --no-markdown} or {@code /markdown off} when
     * you want raw output for copy-paste or debugging.
     *
     * <p>Side-effect: with markdown ON the live char-by-char
     * streaming of the main process is replaced by a buffered
     * commit-time render — code fences and tables need block context,
     * so we can only render once the full assistant turn arrives.
     */
    @Data
    public static class Markdown {
        private boolean enabled = true;
        private String heading = "fg:cyan,bold";
        private String code = "fg:bright-black";
        private String blockquote = "fg:bright-black,italic";
        private String tableBorder = "fg:bright-black";
        /**
         * Max column width for flowing prose (paragraphs, list items,
         * blockquotes). Tables, code fences and heading lines are
         * exempt — they need to keep their own layout. Set to {@code 0}
         * to disable wrapping. Splits at the last space within the
         * budget; very long unbroken tokens are emitted as one
         * over-long line rather than mid-word-cut.
         */
        private int wrapWidth = 120;
    }

    /**
     * Pretty-printed display of local {@link de.mhus.vance.foot.tools.ClientTool}
     * invocations. When {@link #enabled} the foot terminal prints a
     * cosmetic two-line block per tool call — header
     * ({@code ⏺ Read(/path)}) and result tail ({@code   ⎿  Read 1234
     * chars}). Suppress with {@code --no-tool-output} or
     * {@code vance.ui.tool-output.enabled=false}.
     *
     * <p>{@link #header} / {@link #result} accept the same JLine style
     * grammar as the other {@link Colors} entries.
     */
    @Data
    public static class ToolOutput {
        private boolean enabled = true;
        private String header = "fg:cyan,bold";
        private String result = "fg:bright-black";
        /**
         * Print a coloured line-diff after {@code client_file_write} /
         * {@code client_file_edit} succeed: added lines green with a
         * {@code +} prefix, removed lines red with {@code -}, plus a
         * configurable number of context lines around each hunk, and a
         * {@code ...} marker between hunks (and at the edges of the
         * file). Off-by-one with {@link #enabled}: if the tool-output
         * itself is suppressed, the diff is too.
         */
        private boolean diffEnabled = true;
        /** Lines of unchanged context above/below each hunk. */
        private int diffContextLines = 3;
        /** Hard cap on total diff lines emitted; the renderer prints a
         *  truncation marker if exceeded. Prevents flooding the terminal
         *  on huge full-file rewrites. */
        private int diffMaxLines = 200;
        /** Style for "+" added lines. Defaults to light-green background. */
        private String diffAdd = "fg:black,bg:#c8f7c5";
        /** Style for "-" removed lines. Defaults to light-red background. */
        private String diffRemove = "fg:black,bg:#ffcccc";
        /** Style for unchanged context lines (leading space prefix). */
        private String diffContext = "fg:bright-black";
        /** Style for the {@code ...} hunk separator / file-edge markers. */
        private String diffMarker = "fg:white,bg:black,bold";
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
        /**
         * Set by {@link VanceProjectConfigApplier} when
         * {@code defaults.sandbox} is {@code false} in
         * {@code .vancetope/config.yaml}. Read by {@code VanceFootCommand}
         * after {@code applyProjectConfig()} to call
         * {@code permissions.disableSandbox()} — unless the CLI
         * already set {@code --no-sandbox} explicitly (CLI wins).
         */
        private boolean noSandboxDefault = false;
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
         * Recipe name for the recipe cascade. {@code null}/empty falls
         * back to the bundled {@code "default"} recipe.
         */
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

    /**
     * Keep the host machine awake while the brain is working. Long
     * agent turns (research, multi-step worker trees) can run for many
     * minutes with no keyboard/mouse activity, so the OS would suspend
     * to sleep mid-flight and drop the WebSocket. When enabled, foot
     * asks the OS to inhibit <em>idle system sleep</em> for the exact
     * span the {@link de.mhus.vance.foot.ui.BusyIndicator} reports work
     * in flight, then lets it sleep normally again once idle.
     *
     * <p>Implemented via the platform's native, headless inhibitor —
     * {@code caffeinate} (macOS), {@code systemd-inhibit} (Linux),
     * {@code SetThreadExecutionState} through a hidden PowerShell
     * (Windows). No GUI, no window, no synthetic input. The display is
     * intentionally allowed to sleep — only system suspend is blocked.
     */
    @Data
    public static class SleepGuard {
        /**
         * Master switch. Default {@code true}. Set to {@code false} to
         * let the machine follow its normal sleep policy even while a
         * chat round-trip or worker turn is running.
         */
        private boolean enabled = true;

        /**
         * Trailing-edge grace period before the OS inhibitor is actually
         * released once work settles. Default {@code 5m}. Agent turns
         * come in bursts (chat round-trip, then async worker turns), so
         * releasing immediately on every idle would thrash the native
         * inhibitor process on and off between turns — and let the
         * machine try to suspend in the gap. Instead we keep the
         * inhibitor for this span after the last turn; any new work
         * inside the window cancels the pending release. Worst case the
         * host stays awake a few extra minutes after the agent is truly
         * done, which is harmless.
         */
        private Duration linger = Duration.ofMinutes(5);
    }

    /**
     * Conversation audit logging — appends every chat message (USER and
     * ASSISTANT) as a JSON line to a per-session file, so the full
     * conversation is persisted on disk as it happens. Similar to
     * {@code .claude/exports/}, but written live instead of at session end.
     *
     * <p>Files land under {@code <baseDir>/<YYYY>-<MM>/<sessionId>.jsonl}.
     * The year-month directory is derived from the wall-clock at write
     * time, so a session spanning midnight lands in two files — that's
     * intentional (keeps directories browsable by month).
     *
     * <p>Config sources (precedence: {@code application.yaml <
     * .vancetope/config.yaml < CLI flags}):
     * <ul>
     *   <li>{@code vance.conversation-capture.enabled} in
     *       {@code application.yaml}</li>
     *   <li>{@code conversationCapture.enabled} in
     *       {@code .vancetope/config.yaml} (applied by
     *       {@link VanceProjectConfigApplier})</li>
     *   <li>{@code --audit} / {@code --no-audit} CLI flags</li>
     * </ul>
     */
    @Data
    public static class ConversationCapture {
        /** Master switch. When {@code false}, no audit files are written. */
        private boolean enabled = false;
        /**
         * Base directory for audit files. Relative paths resolve against
         * the active {@code .vancetope} directory (project-local or global
         * home, whichever is active). {@code null} or blank defaults to
         * {@code conversations} (i.e. {@code .vancetope/conversations/}).
         */
        private @Nullable String dir;
    }
}


