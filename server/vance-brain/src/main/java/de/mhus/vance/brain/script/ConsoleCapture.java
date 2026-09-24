package de.mhus.vance.brain.script;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Capped in-memory capture for a script's {@code console.log}/{@code console.error}
 * output. Wired as the GraalJS context's {@code out} and {@code err} stream —
 * without it, console output goes to the JVM's stdout (the container log),
 * invisible to the caller (Hactar's agent identity, the run panel, forensics).
 *
 * <p>Thread-safe (the eval thread writes, the runner thread reads at
 * terminal) and bounded: the buffer keeps the <b>tail</b> — when the cap is
 * exceeded the oldest half is dropped, so a misbehaving script cannot ship
 * unbounded memory, while the most recent output (what a reader wants) is
 * preserved. Bytes are UTF-8 decoded; a truncation in the middle of a
 * multi-byte sequence can cost one garbled character — accepted for a
 * diagnostics surface.
 */
final class ConsoleCapture extends OutputStream {

    private static final int TRIM_MARK = 1024;

    private final byte[] buffer;
    private int length;
    private final int cap;
    /** Live line tap — receives every completed line (\n-terminated
     *  events, plus the last unterminated remainder on tail()). May be
     *  null (no tap). Callback failures are swallowed: a live consumer
     *  must never kill the script run. */
    private final @Nullable Consumer<String> lineConsumer;

    ConsoleCapture(int capBytes) {
        this(capBytes, null);
    }

    ConsoleCapture(int capBytes, @Nullable Consumer<String> lineConsumer) {
        this.cap = Math.max(TRIM_MARK, capBytes);
        this.buffer = new byte[cap];
        this.lineConsumer = lineConsumer;
        this.lineScratch = new java.io.ByteArrayOutputStream();
    }

    private final java.io.ByteArrayOutputStream lineScratch;

    @Override
    public synchronized void write(int b) {
        ensure(1);
        buffer[length++] = (byte) b;
        if (lineConsumer != null) {
            if (b == '\n') {
                emitLine(lineScratch.toByteArray());
                lineScratch.reset();
            } else {
                lineScratch.write(b);
            }
        }
    }

    @Override
    public synchronized void write(byte[] src, int off, int len) {
        if (src == null || len <= 0) {
            return;
        }
        int clampedLen = Math.min(len, src.length - off);
        if (clampedLen <= 0) {
            return;
        }
        ensure(clampedLen);
        System.arraycopy(src, off, buffer, length, clampedLen);
        length += clampedLen;
        if (lineConsumer != null) {
            for (int i = 0; i < clampedLen; i++) {
                if (src[off + i] == '\n') {
                    lineScratch.write(src[off + i]);
                    emitLine(lineScratch.toByteArray());
                    lineScratch.reset();
                } else {
                    lineScratch.write(src[off + i] & 0xFF);
                }
            }
        }
    }

    private void emitLine(byte[] raw) {
        try {
            lineConsumer.accept(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // A live consumer must never kill the run.
        }
    }

    /** The captured tail as text — empty string when nothing was printed. */
    public synchronized String tail() {
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Makes room for {@code needed} bytes, dropping the oldest half of the
     * buffer when the cap is hit. A single write larger than the cap keeps
     * only its own tail (the last {@code cap} bytes of it).
     */
    private void ensure(int needed) {
        if (length + needed <= cap) {
            return;
        }
        if (needed >= cap) {
            // Oversized single write: nothing older survives it anyway.
            length = 0;
            return;
        }
        int drop = Math.max(needed, cap / 2);
        System.arraycopy(buffer, drop, buffer, 0, cap - drop);
        length = cap - drop;
    }
}
