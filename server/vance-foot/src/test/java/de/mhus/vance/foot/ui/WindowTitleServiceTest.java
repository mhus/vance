package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowTitleServiceTest {

    @Test
    void defaultFormat_withoutSession_fallsBackToBracketedLabel() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);

        // Default format is "{glyph} {session}"; with the connection set but
        // no session bound, the session placeholder expands to empty and the
        // bare-glyph fallback kicks in so the tab label stays meaningful.
        svc.setConnection("connecting…");

        assertThat(cap.last()).isEqualTo(osc("𝑣 [vance]"));
    }

    @Test
    void defaultFormat_withSession_rendersGlyphAndSession() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");

        svc.setSession("project-x");

        assertThat(cap.last()).isEqualTo(osc("𝑣 project-x"));
    }

    @Test
    void defaultFormat_sessionCleared_collapsesToFallbackLabel() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, true, true);
        svc.setConnection("acme");
        svc.setSession("project-x");

        svc.setSession(null);

        assertThat(cap.last()).isEqualTo(osc("𝑣 [vance]"));

        svc.setSession("   ");

        assertThat(cap.last()).isEqualTo(osc("𝑣 [vance]"));
    }

    @Test
    void emptyFormat_fallsBackToBareLabel() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap, "", true, true);

        svc.setSession("project-x");

        assertThat(cap.last()).isEqualTo(osc("[vance]"));
    }

    @Test
    void customFormat_expandsAllPlaceholders() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap,
                "{glyph} {session} · {connection} · {ide}", true, true);
        svc.setConnection("acme");
        svc.setSession("project-x");

        svc.setIdeAttached(true);

        assertThat(cap.last()).isEqualTo(osc("𝑣 project-x · acme · [ide]"));
    }

    @Test
    void customFormat_emptyIdePlaceholder_expandsToEmpty() {
        Capture cap = new Capture();
        WindowTitleService svc = svc(cap,
                "{glyph} {session}{ide}", true, true);
        svc.setConnection("acme");
        svc.setSession("project-x");

        assertThat(cap.last()).isEqualTo(osc("𝑣 project-x"));

        svc.setIdeAttached(true);

        assertThat(cap.last()).isEqualTo(osc("𝑣 project-x[ide]"));
    }

    @Test
    void tick_whileBusy_alternatesBetweenFilledAndHollowCircle() {
        Capture cap = new Capture();
        BusyIndicator indicator = new BusyIndicator();
        WindowTitleService svc = svc(cap, indicator, true, true);
        svc.setSession("project-x");

        indicator.enter("test");
        svc.tick();
        assertThat(cap.last()).isEqualTo(osc("● project-x"));

        svc.tick();
        assertThat(cap.last()).isEqualTo(osc("○ project-x"));

        svc.tick();
        assertThat(cap.last()).isEqualTo(osc("● project-x"));
    }

    @Test
    void tick_whenBusyClears_snapsBackToIdleGlyph() {
        Capture cap = new Capture();
        BusyIndicator indicator = new BusyIndicator();
        WindowTitleService svc = svc(cap, indicator, true, true);
        svc.setSession("project-x");

        indicator.enter("test");
        svc.tick();
        assertThat(cap.last()).isEqualTo(osc("● project-x"));

        indicator.exit("test");
        svc.tick();
        assertThat(cap.last()).isEqualTo(osc("𝑣 project-x"));
    }

    @Test
    void tick_whileIdleAndUnchanged_emitsNothing() {
        Capture cap = new Capture();
        BusyIndicator indicator = new BusyIndicator();
        WindowTitleService svc = svc(cap, indicator, true, true);
        svc.setSession("project-x");
        cap.events.clear();

        svc.tick();

        assertThat(cap.events).isEmpty();
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
        svc.setSession("project-x");
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

        svc.setSession("abc");

        assertThat(cap.last()).isEqualTo(osc("𝑣 abc"));
    }

    private static String osc(String body) {
        return WindowTitleService.OSC_PREFIX + body + WindowTitleService.OSC_TERMINATOR;
    }

    private static WindowTitleService svc(Capture cap, boolean configEnabled, boolean tty) {
        return svc(cap, new BusyIndicator(), configEnabled, tty);
    }

    private static WindowTitleService svc(Capture cap, BusyIndicator busy,
                                          boolean configEnabled, boolean tty) {
        return svc(cap, busy, null, configEnabled, tty);
    }

    private static WindowTitleService svc(Capture cap, String format,
                                          boolean configEnabled, boolean tty) {
        return svc(cap, new BusyIndicator(), format, configEnabled, tty);
    }

    private static WindowTitleService svc(Capture cap, BusyIndicator busy, String format,
                                          boolean configEnabled, boolean tty) {
        FootConfig cfg = new FootConfig();
        cfg.getUi().getWindowTitle().setEnabled(configEnabled);
        if (format != null) {
            cfg.getUi().getWindowTitle().setFormat(format);
        }
        return new WindowTitleService(cfg, busy, cap::record, () -> tty);
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
