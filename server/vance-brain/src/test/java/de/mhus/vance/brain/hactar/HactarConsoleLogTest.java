package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link HactarConsoleLog}: the in-memory line ring of the CURRENT run
 * (Live-Fund 5) — kick clears, the tap records, the terminal keeps the
 * lines readable, and {@code renderTail} stamps every line with its
 * arrival time so a reading model cannot mistake stale transcript
 * replies for the current output (Live-Fund 5a).
 */
class HactarConsoleLogTest {

    @Test
    void record_andTail_linesInOrderNewestLast() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        log.record("p1", "first\n");
        log.record("p1", "second\n");

        List<HactarConsoleLog.Line> tail = log.tail("p1", 5);
        assertThat(tail).hasSize(2);
        assertThat(tail.get(0).text()).isEqualTo("first");
        assertThat(tail.get(1).text()).isEqualTo("second");
    }

    @Test
    void tail_capsAtMax() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        for (int i = 1; i <= 8; i++) {
            log.record("p1", "line-" + i + "\n");
        }

        List<HactarConsoleLog.Line> tail = log.tail("p1", 3);
        assertThat(tail).extracting(HactarConsoleLog.Line::text).containsExactly("line-6", "line-7", "line-8");
    }

    @Test
    void ring_capsAt100Lines() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        for (int i = 1; i <= 130; i++) {
            log.record("p1", "line-" + i + "\n");
        }

        assertThat(log.tail("p1", 200)).hasSize(100);
        assertThat(log.tail("p1", 1).get(0).text()).isEqualTo("line-130");
    }

    @Test
    void startRun_clearsPreviousLines_andMarksRunning() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        log.record("p1", "old\n");
        assertThat(log.isRunning("p1")).isTrue();

        log.finishRun("p1");
        assertThat(log.isRunning("p1")).isFalse();
        // finish keeps the lines readable for "what did it print?" turns
        assertThat(log.tail("p1", 5)).hasSize(1);

        log.startRun("p1");
        assertThat(log.tail("p1", 5)).isEmpty();
        assertThat(log.isRunning("p1")).isTrue();
    }

    @Test
    void record_ignoresBlankAndMissingKeys() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        log.record(null, "x\n");
        log.record("  ", "x\n");
        log.record("p1", null);
        log.record("p1", "\n");

        assertThat(log.tail("p1", 5)).isEmpty();
        assertThat(log.tail("unknown", 5)).isEmpty();
    }

    @Test
    void renderTail_stampsEachLineWithArrivalTime() {
        HactarConsoleLog log = new HactarConsoleLog();
        log.startRun("p1");
        log.record("p1", "[info] Hello, world! #1\n");
        log.record("p1", "[info] Hello, world! #2\n");

        String rendered = log.renderTail("p1", 5);
        assertThat(rendered).contains("[info] Hello, world! #1");
        assertThat(rendered).contains("[info] Hello, world! #2");
        // two lines, each with an HH:mm:ss stamp prefix
        String[] lines = rendered.split("\n", -1);
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            assertThat(line).matches("\\d{2}:\\d{2}:\\d{2}  \\[info\\] Hello, world! #\\d");
        }
        // stamps are ordered with the text
        assertThat(lines[0]).contains("#1");
        assertThat(lines[1]).contains("#2");
    }

    @Test
    void renderTail_emptyWhenNoLines() {
        HactarConsoleLog log = new HactarConsoleLog();
        assertThat(log.renderTail("p1", 5)).isEmpty();
        log.startRun("p1");
        assertThat(log.renderTail("p1", 5)).isEmpty();
    }
}
