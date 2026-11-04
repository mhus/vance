package de.mhus.vance.brain.wowbagger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A text file of the active work target as one {@link InputStream}, read
 * page by page through {@code file_read} ({@code startLine} / {@code maxLines}).
 *
 * <p>This is how Wowbagger reaches a source that lives wherever the process's
 * work target points (CLIENT, DAEMON, RUNNER, WORK) without knowing which: the
 * fetcher is the {@code file_read} wrapper, which dispatches on the target
 * itself. The stream is consumed once, at import — the run then works on a
 * document, never on the target.
 *
 * <p>Pages are sized generously: a page costs the backend a full pass over
 * the file (line skip, content hash), so few large pages beat many small
 * ones. A page cut by the character cap is trimmed back to its last complete
 * line and the next page starts there, so no record is ever split or lost.
 * Lines are emitted {@code \n}-terminated, whatever the backend joined them
 * with.
 */
final class TargetFileStream extends InputStream {

    /** Lines requested per page. */
    static final int PAGE_LINES = 50_000;

    /** Character cap requested per page — raised above the server default on purpose. */
    static final int PAGE_CHARS = 16 * 1024 * 1024;

    private final String path;
    private final Function<Map<String, Object>, Map<String, Object>> fetch;
    private final int pageLines;
    private final int pageChars;

    private byte[] buf = new byte[0];
    private int pos;
    private long nextLine = 1;
    private boolean done;

    /**
     * @param path  the file, in the work target's own addressing
     * @param fetch the {@code file_read} call: params in, tool result out
     */
    TargetFileStream(String path, Function<Map<String, Object>, Map<String, Object>> fetch) {
        this(path, fetch, PAGE_LINES, PAGE_CHARS);
    }

    TargetFileStream(
            String path, Function<Map<String, Object>, Map<String, Object>> fetch, int pageLines, int pageChars) {
        this.path = path;
        this.fetch = fetch;
        this.pageLines = pageLines;
        this.pageChars = pageChars;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] out, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = Math.min(len, buf.length - pos);
        System.arraycopy(buf, pos, out, off, n);
        pos += n;
        return n;
    }

    /** Loads the next page when the buffer is drained; {@code false} at end of file. */
    private boolean fill() throws IOException {
        while (pos >= buf.length) {
            if (done) {
                return false;
            }
            loadPage();
        }
        return true;
    }

    private void loadPage() throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        params.put("startLine", nextLine);
        params.put("maxLines", pageLines);
        params.put("maxChars", pageChars);
        Map<String, Object> result;
        try {
            result = fetch.apply(params);
        } catch (RuntimeException e) {
            throw new IOException("reading '" + path + "' failed: " + e.getMessage(), e);
        }
        String content = result.get("content") instanceof String s ? s : "";
        if (content.isEmpty()) {
            done = true;
            return;
        }
        boolean truncated = Boolean.TRUE.equals(result.get("truncated"));
        if (truncated) {
            // The char cap cut the page somewhere inside a line: keep only the
            // complete lines, the next page starts at the cut one.
            int lastBreak = content.lastIndexOf('\n');
            if (lastBreak < 0) {
                throw new IOException("line " + nextLine + " of '" + path + "' is longer than the page cap of "
                        + pageChars + " characters");
            }
            content = content.substring(0, lastBreak);
        }
        long lines = countLines(content);
        nextLine += lines;
        if (!truncated && lines < pageLines) {
            done = true;
        }
        buf = (content + "\n").getBytes(StandardCharsets.UTF_8);
        pos = 0;
    }

    /** Number of lines in {@code text}: one more than its line breaks. */
    private static long countLines(@Nullable String text) {
        long breaks = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    breaks++;
                }
            }
        }
        return breaks + 1;
    }
}
