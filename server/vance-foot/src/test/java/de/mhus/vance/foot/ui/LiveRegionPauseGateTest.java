package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.config.FootConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Output gating around {@link LiveRegion#pause()} — the invariant that
 * either JLine or the Lanterna UI owns the terminal, never both. Runs
 * against an unattached region (no TTY in a unit test), which exercises
 * the same paused-branch and flushes through {@code System.out}.
 */
class LiveRegionPauseGateTest {

    private LiveRegion region;
    private ByteArrayOutputStream captured;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        region = new LiveRegion(mock(StatusBar.class), mock(FootConfig.class), mock(ColorResolver.class));
        captured = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void emitStatic_whilePaused_writesNothingToTheTerminal() {
        region.pause();

        region.emitStatic("chat line from the brain");

        assertThat(output()).isEmpty();
    }

    @Test
    void resume_replaysBacklogInOrderBehindAMarker() {
        region.pause();
        region.emitStatic("first");
        region.emitStatic("second");

        region.resume();

        assertThat(output())
                .contains(DeferredOutput.marker(2, 0))
                .containsSubsequence("arrived while the fullscreen UI was open", "first", "second");
    }

    @Test
    void resume_withoutBacklog_emitsNoMarker() {
        region.pause();

        region.resume();

        assertThat(output()).isEmpty();
    }

    @Test
    void pause_isSetEvenWhenUnattached_soOutputStaysGated() {
        assertThat(region.isAttached()).isFalse();

        region.pause();

        assertThat(region.isPaused()).isTrue();
    }

    @Test
    void resume_clearsPausedFlag() {
        region.pause();

        region.resume();

        assertThat(region.isPaused()).isFalse();
    }

    @Test
    void emitStatic_afterResume_reachesTheTerminalAgain() {
        region.pause();
        region.resume();

        region.emitStatic("live again");

        assertThat(output()).contains("live again");
    }

    @Test
    void emitStatic_emptyString_writesBlankLine() {
        // Markdown paragraph spacing relies on blank lines surviving
        // the emit path — an empty payload must not be dropped.
        region.emitStatic("before");
        region.emitStatic("");
        region.emitStatic("after");

        assertThat(output()).isEqualTo("before\n\n\nafter\n");
    }

    @Test
    void emitStatic_emptyString_whilePaused_isKeptInTheBacklog() {
        region.pause();
        region.emitStatic("");
        region.resume();

        assertThat(output()).contains("\n");
    }

    @Test
    void clearScreen_whilePaused_discardsTheBacklog() {
        region.pause();
        region.emitStatic("stale output");

        region.clearScreen();
        region.resume();

        assertThat(output()).isEmpty();
    }

    @Test
    void pause_twice_doesNotLoseTheBacklog() {
        region.pause();
        region.emitStatic("held");
        region.pause();

        region.resume();

        assertThat(output()).contains("held");
    }
}
