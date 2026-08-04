package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Centralised resolver that turns the string style expressions from
 * {@link FootConfig.Colors} into ready-to-use {@link AttributedStyle}
 * instances. Constructed once at boot; callers read the parsed fields
 * directly — no per-call parsing overhead.
 *
 * <p>Every field is {@code @Nullable}: a {@code null} value means "no
 * styling" (terminal default — typically white), matching the semantics
 * of {@link StyleParser#parse(String)}.
 *
 * <p>Before this class existed, each handler built its own
 * {@code AttributedStyle.DEFAULT.foreground(...)} constant with a
 * hardcoded colour. Those constants are now replaced by the fields
 * here, so the user can override every colour via
 * {@code vance.ui.colors.*} in {@code application.yaml} or
 * {@code .vancetope/config.yaml}.
 */
@Component
public class ColorResolver {

    // --- Core chat channels ---
    private final @Nullable AttributedStyle chat;
    private final @Nullable AttributedStyle worker;
    private final @Nullable AttributedStyle info;
    private final @Nullable AttributedStyle verbose;
    private final @Nullable AttributedStyle debug;
    private final @Nullable AttributedStyle warn;
    private final @Nullable AttributedStyle error;

    // --- Reasoning / thoughts ---
    private final @Nullable AttributedStyle thoughts;

    // --- Progress / mode side-channel ---
    private final @Nullable AttributedStyle dim;

    // --- Notification toast severity colours ---
    private final @Nullable AttributedStyle notifyInfo;
    private final @Nullable AttributedStyle notifyWarn;
    private final @Nullable AttributedStyle notifyError;

    // --- Plan / todo box rendering ---
    private final @Nullable AttributedStyle planBorder;
    private final @Nullable AttributedStyle planContent;
    private final @Nullable AttributedStyle planDim;

    // --- Warning boxes ---
    private final @Nullable AttributedStyle sandboxWarn;
    private final @Nullable AttributedStyle permissionWarn;

    // --- Status bar ---
    private final @Nullable AttributedStyle statusBusy;
    private final @Nullable AttributedStyle statusDim;
    private final @Nullable AttributedStyle statusContext;

    // --- Input / prompt area ---
    private final @Nullable AttributedStyle userEcho;
    private final @Nullable AttributedStyle completion;
    private final @Nullable AttributedStyle ghostText;
    private final @Nullable AttributedStyle systemMessage;

    public ColorResolver(FootConfig config) {
        FootConfig.Colors c = config.getUi().getColors();
        this.chat = StyleParser.parse(c.getChat());
        this.worker = StyleParser.parse(c.getWorker());
        this.info = StyleParser.parse(c.getInfo());
        this.verbose = StyleParser.parse(c.getVerbose());
        this.debug = StyleParser.parse(c.getDebug());
        this.warn = StyleParser.parse(c.getWarn());
        this.error = StyleParser.parse(c.getError());
        this.thoughts = StyleParser.parse(c.getThoughts());
        this.dim = StyleParser.parse(c.getDim());
        this.notifyInfo = StyleParser.parse(c.getNotifyInfo());
        this.notifyWarn = StyleParser.parse(c.getNotifyWarn());
        this.notifyError = StyleParser.parse(c.getNotifyError());
        this.planBorder = StyleParser.parse(c.getPlanBorder());
        this.planContent = StyleParser.parse(c.getPlanContent());
        this.planDim = StyleParser.parse(c.getPlanDim());
        this.sandboxWarn = StyleParser.parse(c.getSandboxWarn());
        this.permissionWarn = StyleParser.parse(c.getPermissionWarn());
        this.statusBusy = StyleParser.parse(c.getStatusBusy());
        this.statusDim = StyleParser.parse(c.getStatusDim());
        this.statusContext = StyleParser.parse(c.getStatusContext());
        this.userEcho = StyleParser.parse(c.getUserEcho());
        this.completion = StyleParser.parse(c.getCompletion());
        this.ghostText = StyleParser.parse(c.getGhostText());
        this.systemMessage = StyleParser.parse(c.getSystemMessage());
    }

    public @Nullable AttributedStyle chat() { return chat; }
    public @Nullable AttributedStyle worker() { return worker; }
    public @Nullable AttributedStyle info() { return info; }
    public @Nullable AttributedStyle verbose() { return verbose; }
    public @Nullable AttributedStyle debug() { return debug; }
    public @Nullable AttributedStyle warn() { return warn; }
    public @Nullable AttributedStyle error() { return error; }
    public @Nullable AttributedStyle thoughts() { return thoughts; }
    public @Nullable AttributedStyle dim() { return dim; }
    public @Nullable AttributedStyle notifyInfo() { return notifyInfo; }
    public @Nullable AttributedStyle notifyWarn() { return notifyWarn; }
    public @Nullable AttributedStyle notifyError() { return notifyError; }
    public @Nullable AttributedStyle planBorder() { return planBorder; }
    public @Nullable AttributedStyle planContent() { return planContent; }
    public @Nullable AttributedStyle planDim() { return planDim; }
    public @Nullable AttributedStyle sandboxWarn() { return sandboxWarn; }
    public @Nullable AttributedStyle permissionWarn() { return permissionWarn; }
    public @Nullable AttributedStyle statusBusy() { return statusBusy; }
    public @Nullable AttributedStyle statusDim() { return statusDim; }
    public @Nullable AttributedStyle statusContext() { return statusContext; }
    public @Nullable AttributedStyle userEcho() { return userEcho; }
    public @Nullable AttributedStyle completion() { return completion; }
    public @Nullable AttributedStyle ghostText() { return ghostText; }
    public @Nullable AttributedStyle systemMessage() { return systemMessage; }

    /**
     * Build a styled {@link AttributedStringBuilder} pre-loaded with the
     * given style, or a bare one when {@code style} is {@code null}.
     * Convenience for callers that always append text after setting the
     * style.
     */
    public static AttributedStringBuilder styled(@Nullable AttributedStyle style) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (style != null) sb.style(style);
        return sb;
    }

    // ───────────────────────────────────────────────────────────────
    //  ANSI string helpers — for classes that build raw ANSI strings
    //  (StatusBar, LiveRegion, ChatRepl, SessionResumeFlow) instead of
    //  using AttributedStringBuilder.  These convert an AttributedStyle
    //  into the SGR escape sequence needed to activate it.
    // ───────────────────────────────────────────────────────────────

    /** The ANSI SGR reset sequence: {@code ESC[0m}. */
    public static final String ANSI_RESET = "\u001b[0m";

    /**
     * Convert an {@link AttributedStyle} into the ANSI SGR escape
     * sequence that activates it (without the trailing reset).
     * Returns an empty string when {@code style} is {@code null} so
     * callers can concatenate unconditionally.
     *
     * <p>{@link AttributedStyle#toAnsi()} returns only the SGR
     * <em>parameter</em> body (e.g. {@code "33"}, {@code "90;2"}) — not
     * a usable escape sequence. We wrap it into the full CSI form
     * {@code ESC[<params>m} here. A blank body (the DEFAULT style yields
     * a single space) means "no styling" → empty string, so callers can
     * concatenate unconditionally.
     */
    public static String toAnsi(@Nullable AttributedStyle style) {
        if (style == null) return "";
        String sgr = style.toAnsi();
        if (sgr == null || sgr.isBlank()) return "";
        return "\u001b[" + sgr + "m";
    }

    /**
     * Wrap {@code text} with the SGR sequence for {@code style} and a
     * trailing reset.  When {@code style} is {@code null}, returns the
     * text unchanged (no escape codes).
     */
    public static String wrap(@Nullable AttributedStyle style, String text) {
        if (style == null) return text;
        return toAnsi(style) + text + ANSI_RESET;
    }
}
