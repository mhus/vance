package de.mhus.vance.brain.wowbagger;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Record windows out of one source document, read through a stream that is
 * kept open between chunks.
 *
 * <p>The pool hands out windows strictly in order at the pointer, so one
 * sequential reader serves the whole run: no re-open and no line skip per
 * chunk. The three exceptions are handled here and nowhere else:
 * <ul>
 *   <li><b>First read / resume</b> — the reader starts at record 0 and skips
 *       forward to the requested start (the only O(n) moment of a run).</li>
 *   <li><b>Earlier window</b> (a retried chunk behind the pointer) — read with
 *       a short-lived second stream; the main reader keeps its position.</li>
 *   <li><b>Broken stream</b> — closed, re-opened once, skipped to the
 *       requested start again.</li>
 * </ul>
 * Not thread-safe: the pool calls it under the run's handle lock.
 */
final class DocLineReader implements Closeable {

    /** Opens the source from its first byte. */
    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    private final Opener opener;
    private @Nullable BufferedReader reader;
    /** Index of the next record {@link #reader} would return. */
    private long position;

    DocLineReader(Opener opener) {
        this.opener = opener;
    }

    /**
     * Up to {@code count} records starting at {@code startRecord}; fewer (or
     * none) at the end of the source — EOF is authoritative.
     */
    List<String> read(long startRecord, int count) throws IOException {
        if (startRecord < position) {
            return readFresh(startRecord, count);
        }
        try {
            return readSequential(startRecord, count);
        } catch (IOException e) {
            close();
            return readSequential(startRecord, count);
        }
    }

    /** Number of records, from a counting pass over a fresh stream. */
    long count() throws IOException {
        long lines = 0;
        try (BufferedReader r = newReader()) {
            while (r.readLine() != null) {
                lines++;
            }
        }
        return lines;
    }

    @SuppressWarnings("PMD.CloseResource") // local view of the field; closed by close()
    private List<String> readSequential(long startRecord, int count) throws IOException {
        BufferedReader r = reader;
        if (r == null) {
            r = newReader();
            reader = r;
            position = 0;
        }
        while (position < startRecord) {
            if (r.readLine() == null) {
                return List.of();
            }
            position++;
        }
        List<String> out = new ArrayList<>(count);
        String line;
        while (out.size() < count && (line = r.readLine()) != null) {
            out.add(line);
            position++;
        }
        return out;
    }

    private List<String> readFresh(long startRecord, int count) throws IOException {
        List<String> out = new ArrayList<>(count);
        try (BufferedReader r = newReader()) {
            for (long i = 0; i < startRecord; i++) {
                if (r.readLine() == null) {
                    return out;
                }
            }
            String line;
            while (out.size() < count && (line = r.readLine()) != null) {
                out.add(line);
            }
        }
        return out;
    }

    private BufferedReader newReader() throws IOException {
        return new BufferedReader(new InputStreamReader(opener.open(), StandardCharsets.UTF_8));
    }

    @Override
    @SuppressWarnings("PMD.CloseResource") // local view of the field, closed right below
    public void close() {
        BufferedReader r = reader;
        reader = null;
        position = 0;
        if (r != null) {
            try {
                r.close();
            } catch (IOException ignored) {
                // a stream that already broke has nothing left to release
            }
        }
    }
}
