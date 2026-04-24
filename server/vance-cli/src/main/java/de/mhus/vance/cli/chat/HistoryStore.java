package de.mhus.vance.cli.chat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Persistent input-line history — the list of things the user typed and
 * submitted, the same set that ARROW_UP / ARROW_DOWN walks through.
 *
 * <p>Plain text, one submitted line per file line — the same shape shells
 * use for {@code .bash_history}. Deliberately does NOT persist the chat
 * render log: replaying old chat messages at startup just clutters the view
 * with stale timestamps and hides what is actually happening in the current
 * session. The input history is the small, useful slice that survives.
 *
 * <p>Append is best-effort: IO failures are swallowed so the UI stays
 * responsive if the file path is un-writable.
 */
public final class HistoryStore {

    private final Path file;
    private final Object writeLock = new Object();

    public HistoryStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Reads all lines from the file, oldest first. Missing file yields an
     * empty list. Blank lines are skipped — they are not valid inputs.
     */
    public List<String> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        return raw.stream().filter(s -> !s.isEmpty()).toList();
    }

    /** Appends a single submitted line. Newlines in {@code line} are stripped. */
    public void append(String line) {
        String sanitized = line.replace("\n", " ").replace("\r", " ");
        if (sanitized.isEmpty()) {
            return;
        }
        synchronized (writeLock) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedWriter w = Files.newBufferedWriter(file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    w.write(sanitized);
                    w.newLine();
                }
            } catch (IOException ignored) {
                // swallow — see javadoc
            }
        }
    }

    /**
     * Utility for callers that want a path relative to a base config file,
     * e.g. {@code /foo/wile.coyote.yaml} → {@code /foo/wile.coyote.history}.
     */
    public static @Nullable Path defaultPathBesideConfig(@Nullable Path configPath) {
        if (configPath == null) {
            return null;
        }
        Path absolute = configPath.toAbsolutePath();
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return absolute.resolveSibling(stem + ".history");
    }
}
