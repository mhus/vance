package de.mhus.vance.brain.wowbagger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocLineReaderTest {

    private static final String TEXT = "r0\nr1\nr2\nr3\nr4\n";

    private final AtomicInteger opens = new AtomicInteger();

    private DocLineReader reader() {
        return new DocLineReader(() -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void sequentialWindowsShareOneStream() throws IOException {
        DocLineReader r = reader();

        assertThat(r.read(0, 2)).containsExactly("r0", "r1");
        assertThat(r.read(2, 2)).containsExactly("r2", "r3");
        assertThat(r.read(4, 2)).containsExactly("r4");
        assertThat(r.read(5, 2)).isEmpty();

        assertThat(opens.get()).as("one open for the whole run").isEqualTo(1);
    }

    @Test
    void resumeSkipsForwardToThePointer() throws IOException {
        assertThat(reader().read(3, 2)).containsExactly("r3", "r4");
    }

    @Test
    void anEarlierWindowUsesASecondStreamAndKeepsThePosition() throws IOException {
        DocLineReader r = reader();
        r.read(0, 3);

        assertThat(r.read(1, 2)).as("retried chunk behind the pointer").containsExactly("r1", "r2");
        assertThat(r.read(3, 2)).as("main reader carries on").containsExactly("r3", "r4");
        assertThat(opens.get()).isEqualTo(2);
    }

    @Test
    void aBrokenStreamIsReopenedOnce() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        DocLineReader r = new DocLineReader(() -> {
            if (calls.incrementAndGet() == 1) {
                return new InputStream() {
                    private int served;

                    @Override
                    public int read() throws IOException {
                        if (served++ < 3) {
                            return 'x';
                        }
                        throw new IOException("connection reset");
                    }
                };
            }
            return new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8));
        });

        assertThat(r.read(1, 2)).containsExactly("r1", "r2");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void countsRecordsOnAFreshStream() throws IOException {
        DocLineReader r = reader();
        r.read(0, 1);

        assertThat(r.count()).isEqualTo(5);
        assertThat(r.read(1, 1)).as("the long-lived reader is untouched").containsExactly("r1");
    }

    @Test
    void closeResetsSoTheNextReadStartsOver() throws IOException {
        DocLineReader r = reader();
        r.read(0, 3);
        r.close();

        assertThat(r.read(0, 1)).containsExactly("r0");
        assertThat(List.of(opens.get())).containsExactly(2);
    }
}
