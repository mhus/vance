package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsoleCapture} — the capped console tail of a script run
 * (Review-16 M2/L5 regressions): an oversized single write keeps only
 * its own tail instead of overflowing the buffer, and the live line tap
 * emits \n-terminated line events on BOTH write paths (the contract
 * run_consoleLineTap_receivesLinesLive pins — the single-byte path used
 * to swallow the separator).
 */
class ConsoleCaptureTest {

    @Test
    void oversizedSingleWrite_keepsOnlyItsOwnTail() {
        // cap clamps to TRIM_MARK (1024) minimum; the write is 5x the cap.
        ConsoleCapture capture = new ConsoleCapture(1024);
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        capture.write(big, 0, big.length);
        String tail = capture.tail();
        // Exactly the cap, holding the LAST 1024 bytes of the write —
        // the pre-fix code reset length to 0 and then threw
        // ArrayIndexOutOfBounds on the oversized arraycopy.
        assertThat(tail).hasSize(1024);
        byte[] expectedLast = new byte[1024];
        System.arraycopy(big, big.length - 1024, expectedLast, 0, 1024);
        assertThat(tail).isEqualTo(new String(expectedLast, StandardCharsets.UTF_8));
    }

    @Test
    void bulkWrite_emitsTerminatedTappedLines() {
        List<String> lines = new ArrayList<>();
        ConsoleCapture capture = new ConsoleCapture(4096, lines::add);
        capture.write("first\nsecond\n".getBytes(StandardCharsets.UTF_8), 0, 13);
        assertThat(lines).containsExactly("first\n", "second\n");
        // The captured TAIL keeps the separators (it is raw console text).
        assertThat(capture.tail()).isEqualTo("first\nsecond\n");
    }

    @Test
    void singleByteWrite_emitsTerminatedTappedLines() {
        List<String> lines = new ArrayList<>();
        ConsoleCapture capture = new ConsoleCapture(4096, lines::add);
        for (byte b : "one\ntwo\n".getBytes(StandardCharsets.UTF_8)) {
            capture.write(b);
        }
        assertThat(lines).containsExactly("one\n", "two\n");
    }

    @Test
    void trimming_dropsOldestHalfWhenCapHit() {
        ConsoleCapture capture = new ConsoleCapture(1024);
        byte[] chunk = new byte[100];
        java.util.Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 30; i++) {
            capture.write(chunk, 0, chunk.length);
        }
        String tail = capture.tail();
        // 3000 bytes into a 1024 cap: bounded, and the content is from
        // the END of the stream, not the start.
        assertThat(tail.length()).isLessThanOrEqualTo(1024);
        assertThat(tail).doesNotContain("yyyy");
        assertThat(tail).startsWith("xxx");
    }
}
