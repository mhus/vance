package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sequence numbering and backlog semantics a remote watcher depends on: it
 * must be able to tell "nothing new" from "I missed lines", which is the whole
 * reason {@code seq} exists next to the bounded ring.
 */
class ChatTerminalSeqTest {

    private ChatTerminal terminal() {
        FootConfig config = new FootConfig();
        // Truncation would rewrite line text and make the assertions about
        // *which* lines came back harder to read.
        config.getUi().setLineMaxChars(0);
        // Detached region → output falls through to stdout, which is what we
        // want: this test is about the recorded ring, not the rendering.
        return new ChatTerminal(config, org.mockito.Mockito.mock(LiveRegion.class));
    }

    @Test
    void record_assignsMonotonicSequenceNumbers() {
        ChatTerminal terminal = terminal();

        terminal.info("first");
        terminal.info("second");
        terminal.info("third");

        assertThat(terminal.tail(10)).extracting(ChatTerminal.Line::seq)
                .containsExactly(1L, 2L, 3L);
        assertThat(terminal.lastSeq()).isEqualTo(3L);
    }

    @Test
    void since_returnsOnlyNewerLines() {
        ChatTerminal terminal = terminal();
        terminal.info("a");
        terminal.info("b");
        terminal.info("c");

        ChatTerminal.Backlog backlog = terminal.since(1L, 100);

        assertThat(backlog.lines()).extracting(ChatTerminal.Line::text)
                .containsExactly("b", "c");
        assertThat(backlog.truncated()).isFalse();
    }

    @Test
    void since_zeroAnchorIsNotAGap() {
        ChatTerminal terminal = terminal();
        terminal.info("a");

        // A fresh watcher asks for "whatever you have" — that is not a gap,
        // and reporting one would put a scary marker on every first attach.
        assertThat(terminal.since(0L, 100).truncated()).isFalse();
    }

    @Test
    void since_reportsTruncationWhenLimitCutsTheBacklog() {
        ChatTerminal terminal = terminal();
        terminal.info("a");
        terminal.info("b");
        terminal.info("c");

        ChatTerminal.Backlog backlog = terminal.since(0L, 2);

        assertThat(backlog.lines()).extracting(ChatTerminal.Line::text).containsExactly("b", "c");
        assertThat(backlog.truncated()).isTrue();
    }

    @Test
    void since_reportsTruncationWhenAnchorFellOutOfTheRing() {
        ChatTerminal terminal = terminal();
        // Overflow the ring so the oldest retained line is far past seq 1.
        for (int i = 0; i < 600; i++) {
            terminal.info("line-" + i);
        }

        ChatTerminal.Backlog backlog = terminal.since(1L, 1000);

        assertThat(backlog.truncated())
                .as("anchor 1 is long evicted — the watcher must learn it missed lines")
                .isTrue();
    }

    @Test
    void lineListener_seesEveryRecordedLine() {
        ChatTerminal terminal = terminal();
        List<String> seen = new ArrayList<>();
        terminal.addLineListener(line -> seen.add(line.text()));

        terminal.info("one");
        terminal.warn("two");

        assertThat(seen).containsExactly("one", "two");
    }

    @Test
    void lineListener_throwingListenerDoesNotBreakTheTerminal() {
        ChatTerminal terminal = terminal();
        terminal.addLineListener(line -> {
            throw new IllegalStateException("sink is broken");
        });

        terminal.info("still recorded");

        assertThat(terminal.tail(10)).extracting(ChatTerminal.Line::text)
                .containsExactly("still recorded");
    }

    @Test
    void removeLineListener_stopsDelivery() {
        ChatTerminal terminal = terminal();
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<ChatTerminal.Line> listener = line -> seen.add(line.text());
        terminal.addLineListener(listener);

        terminal.info("before");
        terminal.removeLineListener(listener);
        terminal.info("after");

        assertThat(seen).containsExactly("before");
    }
}
