package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.config.FootConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The state hand-off between {@link LiveRegion#resume()} and the replacement
 * input reader. Two gates are involved and they are deliberately separate: the
 * <em>output</em> gate reopens synchronously in {@code resume()}, while the
 * <em>input</em> gate stays closed for the short window in which the new
 * reader discards the asynchronous residue of a Lanterna excursion.
 *
 * <p>Needs an attached region — the unattached fallback in {@code resume()}
 * never reaches the hand-off code. The terminal is a mock whose reader keeps
 * producing bytes, which holds the drain window open for its full duration so
 * the assertions land inside it.
 */
class LiveRegionResumeHandoffTest {

    private static final String DISABLE_BRACKETED_PASTE = "\033[?2004l";

    private LiveRegion region;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() throws Exception {
        out = new ByteArrayOutputStream();

        NonBlockingReader reader = mock(NonBlockingReader.class);
        // The hand-off drain reads with a quiet timeout: keep answering so the
        // drain runs its full budget instead of finishing instantly.
        when(reader.read(anyLong())).thenAnswer(invocation -> {
            sleepQuietly(20);
            return (int) 'x';
        });
        // The regular input loop must not spin — park it until the test ends.
        when(reader.read()).thenAnswer(invocation -> {
            sleepQuietly(5_000);
            return -1;
        });

        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("xterm");
        when(terminal.output()).thenReturn(out);
        when(terminal.reader()).thenReturn(reader);
        when(terminal.getAttributes()).thenReturn(new Attributes());
        when(terminal.getWidth()).thenReturn(80);
        when(terminal.getHeight()).thenReturn(24);

        StatusBar statusBar = mock(StatusBar.class);
        when(statusBar.buildStatusLine(anyInt(), anyInt())).thenReturn("status");
        when(statusBar.buildHintsRow(anyInt())).thenReturn("hints");

        region = new LiveRegion(statusBar, new FootConfig(), mock(ColorResolver.class));
        region.attach(terminal);
        assertThat(region.isAttached()).isTrue();
    }

    @AfterEach
    void tearDown() {
        region.detach();
    }

    @Test
    void resume_reopensTheOutputGateBeforeTheInputDrainFinishes() {
        region.pause();
        region.resume();
        out.reset();

        // The replacement reader is still inside its drain window here. Output
        // arriving now used to go into the deferred backlog with no later
        // flush to release it — the chunks simply vanished from the scrollback.
        region.emitStatic("chunk after the excursion");

        assertThat(region.isPaused()).isFalse();
        assertThat(written()).contains("chunk after the excursion");
    }

    @Test
    void pause_duringTheHandoffWindow_stillHandsTheTerminalOver() {
        region.pause();
        region.resume();
        out.reset();

        // Second excursion while the previous reader is still draining. This
        // must tear the region down for real — a no-op return would leave the
        // animator painting and our reader competing with Lanterna.
        region.pause();

        assertThat(written()).contains(DISABLE_BRACKETED_PASTE);
    }

    private String written() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
