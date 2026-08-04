package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import org.springframework.stereotype.Component;

/**
 * Runtime toggle for thinking/reasoning output visibility. Initialised
 * from {@link FootConfig.Ui#isShowThoughts()} at startup (default
 * {@code true}), then flipped on/off by the user via the Ctrl+T
 * keybinding (see {@code LiveRegion} / {@code ChatRepl}). The state
 * persists for the session — it is not reset per message.
 *
 * <p>Both the live reasoning stream ({@link StreamingDisplay}) and the
 * end-of-turn thoughts block ({@code ChatMessageAppendedHandler}) consult
 * this toggle instead of the static config value, so pressing Ctrl+T
 * takes effect immediately for the next reasoning chunk / committed
 * message.
 */
@Component
public class ThinkingVisibility {

    private volatile boolean showing;

    public ThinkingVisibility(FootConfig config) {
        this.showing = config.getUi().isShowThoughts();
    }

    /** Whether thinking/reasoning output is currently visible. */
    public boolean isShowing() {
        return showing;
    }

    /** Flip the toggle and return the new state. */
    public boolean toggle() {
        showing = !showing;
        return showing;
    }

    /** Explicitly set the toggle state. */
    public void setShowing(boolean showing) {
        this.showing = showing;
    }
}
