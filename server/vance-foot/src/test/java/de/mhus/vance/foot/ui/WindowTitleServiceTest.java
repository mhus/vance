package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowTitleServiceTest {

    @Test
    void setConnection_emitsOscEscapeWithPrefixAndConnection() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);

        svc.setConnection("connecting…");

        assertThat(cap.last()).isEqualTo(osc("vance-foot · connecting…"));
    }

    @Test
    void setSession_appendsSessionSegmentAfterConnection() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");

        svc.setSession("project-x");

        assertThat(cap.last()).isEqualTo(osc("vance-foot · acme · project-x"));
    }

    @Test
    void setIdeAttached_appendsIdeSuffix() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");
        svc.setSession("project-x");

        svc.setIdeAttached(true);

        assertThat(cap.last()).isEqualTo(osc("vance-foot · acme · project-x · [ide]"));
    }

    @Test
    void setSessionNullOrBlank_dropsSessionSegment() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");
        svc.setSession("project-x");

        svc.setSession(null);

        assertThat(cap.last()).isEqualTo(osc("vance-foot · acme"));

        svc.setSession("   ");

        assertThat(cap.last()).isEqualTo(osc("vance-foot · acme"));
    }

    @Test
    void setIdeAttachedFalse_clearsIdeSuffix() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");
        svc.setIdeAttached(true);

        svc.setIdeAttached(false);

        assertThat(cap.last()).isEqualTo(osc("vance-foot · acme"));
    }

    @Test
    void disabledConfig_suppressesAllWrites() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, false, true);

        svc.setConnection("connecting…");
        svc.setSession("project-x");
        svc.setIdeAttached(true);

        assertThat(cap.events).isEmpty();
    }

    @Test
    void noTty_suppressesAllWrites() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, false);

        svc.setConnection("connecting…");

        assertThat(cap.events).isEmpty();
    }

    @Test
    void resetOnShutdown_emitsEmptyTitle() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");
        cap.events.clear();

        svc.reset();

        assertThat(cap.last())
                .startsWith(WindowTitleService.OSC_PREFIX)
                .endsWith(WindowTitleService.OSC_TERMINATOR);
        String body = cap.last().substring(
                WindowTitleService.OSC_PREFIX.length(),
                cap.last().length() - WindowTitleService.OSC_TERMINATOR.length());
        assertThat(body).isEmpty();
    }

    @Test
    void sanitize_stripsControlCharactersFromLabels() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);

        // BEL + ESC must not leak into the OSC payload.
        svc.setConnection("abc");

        assertThat(cap.last()).isEqualTo(osc("vance-foot · abc"));
    }

    private static String osc(String body) {
        return WindowTitleService.OSC_PREFIX + body + WindowTitleService.OSC_TERMINATOR;
    }

    private static WindowTitleService svc(Capture cap, boolean configEnabled, boolean tty) {
        FootConfig cfg = new FootConfig();
        cfg.getUi().getWindowTitle().setEnabled(configEnabled);
        return new WindowTitleService(cfg, cap::record, () -> tty);
    }

    private static final class Capture {
        final List<String> events = new ArrayList<>();
        void record(String escape) {
            events.add(escape);
        }
        String last() {
            return events.get(events.size() - 1);
        }
    }
}
