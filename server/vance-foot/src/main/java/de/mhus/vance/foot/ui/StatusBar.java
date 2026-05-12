package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ide.IdeSelectionState;
import de.mhus.vance.foot.session.SessionService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Content provider for the bottom live region. Produces the two
 * always-present rows {@link LiveRegion} composes around the input
 * area:
 *
 * <ul>
 *   <li>{@link #buildStatusLine(int, int)} — the spinner / "thinking"
 *       row above the input. When the active chat process is busy,
 *       shows a phrase, animated spinner, and elapsed time; when idle,
 *       falls back to the current IDE selection if available.</li>
 *   <li>{@link #buildHintsRow(int)} — the bottom-most row of the live
 *       region. Left side carries keyboard hints, right side carries
 *       the {@code session=… · project=… · process=…} context.</li>
 * </ul>
 *
 * <p>This class is intentionally render-free now: it returns
 * already-styled strings (with raw ANSI SGR escapes) that
 * {@link LiveRegion} pastes into its erase/redraw cycle. The old
 * DECSTBM-based renderer is gone; see
 * {@code readme/foot-status-bar-rendering.md} for the rationale.
 */
@Component
public class StatusBar {

    /** ESC byte as a Java Unicode escape — survives source-file
     * roundtrips that strip raw {@code 0x1b} bytes from string literals. */
    private static final String ESC = "\u001b";

    /** 9-frame bouncing-dot animation — left, right, back. */
    private static final String[] FRAMES = {
            "●○○○○",
            "○●○○○",
            "○○●○○",
            "○○○●○",
            "○○○○●",
            "○○○●○",
            "○○●○○",
            "○●○○○"
    };

    private final SessionService sessions;
    private final BusyIndicator busy;
    private final ThinkingPhrases phrases;
    private final ObjectProvider<IdeSelectionState> ideSelection;
    private final FootConfig config;

    /** Wall-clock millis when the current busy cycle started, or {@code -1} when idle. */
    private volatile long busyStartedMillis = -1;
    /** Phrase chosen at the most recent idle → busy transition. Stable for the whole busy period. */
    private volatile String currentPhrase = "thinking";

    public StatusBar(SessionService sessions,
                     BusyIndicator busy,
                     ThinkingPhrases phrases,
                     ObjectProvider<IdeSelectionState> ideSelection,
                     FootConfig config) {
        this.sessions = sessions;
        this.busy = busy;
        this.phrases = phrases;
        this.ideSelection = ideSelection;
        this.config = config;
    }

    /**
     * Status row: when busy, shows the phrase + animated spinner + elapsed
     * time; when idle, falls back to the IDE selection (or a blank line
     * if no selection). The {@code frame} parameter is the animation
     * counter from {@link LiveRegion}; we choose the spinner glyph from
     * it.
     */
    public String buildStatusLine(int width, int frame) {
        boolean isBusy = busy.isBusy();
        if (isBusy) {
            if (busyStartedMillis < 0) {
                busyStartedMillis = System.currentTimeMillis();
                currentPhrase = phrases.random();
            }
        } else {
            busyStartedMillis = -1;
        }

        if (isBusy) {
            String marker = config.getUi().getStatusBar().isAnimated()
                    ? FRAMES[Math.floorMod(frame, FRAMES.length)]
                    : "●";
            long elapsed = (System.currentTimeMillis() - busyStartedMillis) / 1000;
            String line = ESC + "[33m· " + currentPhrase + "… " + marker
                    + ESC + "[0m  " + ESC + "[2m(" + elapsed + "s)" + ESC + "[0m";
            return clamp(line, width, true);
        }
        String selection = ideSelectionText();
        if (selection.isEmpty()) {
            return "";
        }
        return clamp(ESC + "[36m" + selection + ESC + "[0m", width, true);
    }

    /**
     * Hints row: left side carries keyboard help, right side carries the
     * session/project/process context — justified to opposite edges
     * (space-between). On too-narrow terminals, one side is dropped.
     */
    public String buildHintsRow(int width) {
        String leftPlain = buildLeftHints();
        String rightPlain = buildRightContext();
        int leftLen = leftPlain.length();
        int rightLen = rightPlain.length();
        if (leftLen + rightLen + 1 > width) {
            // Doesn't fit — drop one side.
            if (rightLen <= width) {
                return ESC + "[36m" + rightPlain + ESC + "[0m";
            }
            return ESC + "[2m" + clamp(leftPlain, width, false) + ESC + "[0m";
        }
        int pad = width - leftLen - rightLen;
        return ESC + "[2m" + leftPlain + repeat(' ', pad)
                + ESC + "[0m" + ESC + "[36m" + rightPlain + ESC + "[0m";
    }

    private String buildLeftHints() {
        return "  Ctrl-D to quit · Enter to send";
    }

    private String buildRightContext() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return " — no session — ";
        }
        StringBuilder b = new StringBuilder();
        if (bound.icon() != null && !bound.icon().isBlank()) {
            b.append(bound.icon()).append(' ');
        }
        if (bound.title() != null && !bound.title().isBlank()) {
            b.append(bound.title()).append("  ·  ");
        }
        b.append("session=").append(bound.sessionId());
        b.append(" · project=").append(bound.projectId());
        String active = sessions.activeProcess();
        b.append(" · process=").append(active == null ? "—" : active);
        return b.toString();
    }

    private String ideSelectionText() {
        IdeSelectionState state = ideSelection.getIfAvailable();
        return state == null ? "" : state.displayString().orElse("");
    }

    private static String clamp(String s, int width, boolean considerEscapes) {
        if (!considerEscapes) {
            return s.length() <= width ? s : s.substring(0, width);
        }
        // Visible-length aware clamp. Counts non-ESC characters; cuts at
        // visible width. ESC sequences pass through.
        int len = 0;
        int i = 0;
        int n = s.length();
        while (i < n && len < width) {
            char c = s.charAt(i);
            if (c == 0x1b) {
                i++;
                if (i < n && s.charAt(i) == '[') {
                    i++;
                    while (i < n) {
                        char x = s.charAt(i);
                        i++;
                        if ((x >= 'A' && x <= 'Z') || (x >= 'a' && x <= 'z')) break;
                    }
                } else if (i < n) {
                    i++;
                }
            } else {
                len++;
                i++;
            }
        }
        return i >= n ? s : s.substring(0, i);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Compat shim — kept so existing callers don't have to update.
     *  No-op now: {@link LiveRegion} drives the animation loop. */
    @SuppressWarnings("unused")
    public void refresh() {
        // intentional no-op: LiveRegion has its own ticker.
    }

    /** Unused legacy method kept for compatibility; see {@link LiveRegion}. */
    @SuppressWarnings("unused")
    public Object writeLock() {
        return new Object();
    }
}
