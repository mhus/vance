package de.mhus.vance.foot.markdown;

import de.mhus.vance.foot.config.FootConfig;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Runtime toggle for the lite markdown renderer. Initial value comes from
 * {@code vance.ui.markdown.enabled} (default {@code true}); the user can
 * flip it at runtime via {@code /markdown on|off}.
 *
 * <p>Held as a Spring singleton so the few collaborators that care
 * ({@link MarkdownAnsiRenderer}, {@code ChatTerminal},
 * {@code StreamingDisplay}, {@code MarkdownCommand}) share one truth.
 */
@Component
public class MarkdownRenderState {

    private final FootConfig config;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    public MarkdownRenderState(FootConfig config) {
        this.config = config;
    }

    @PostConstruct
    void init() {
        enabled.set(config.getUi().getMarkdown().isEnabled());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }
}
