package de.mhus.vance.liver.cli;

import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup history <project>/<path>}.
 *
 * <p>Walks every snapshot under the target and follows the
 * {@code lineageId} of {@code <project>/<path>}, oldest first. One row
 * per version: a new {@code contentVersion} or a rename — not one per
 * snapshot the unchanged document happens to lie in.
 *
 * <p>Follows renames: the same {@code lineageId} stays stable across
 * path changes, so a document renamed from {@code notes/old.md} to
 * {@code notes/new.md} shows up under both names in the history.
 *
 * <p>This is manifest-only — no file reads, no server calls.
 */
@Command(
        name = "history",
        mixinStandardHelpOptions = true,
        description = "Show version history of one document across snapshots (oldest first).")
@NullMarked
public final class HistoryCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    @picocli.CommandLine.Parameters(arity = "1", description = "Document reference: <project>/<path>")
    private @Nullable String ref;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(HistoryCommand.class.getName());

    @Override
    public Integer call() {
        if (ref == null || !ref.contains("/")) {
            err.println("Usage: history <project>/<path>");
            return 4;
        }
        String project = ref.substring(0, ref.indexOf('/'));
        String path = ref.substring(ref.indexOf('/') + 1);

        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }
        Path target = Path.of(cfg.target);
        if (!Files.exists(target)) {
            out.println("No snapshots yet.");
            return 0;
        }

        List<Path> snapshots;
        try (var stream = Files.list(target)) {
            snapshots = stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            err.println("Cannot list snapshots: " + e.getMessage());
            return 2;
        }

        // First pass: find the lineageId from the latest snapshot that
        // has this (project, path).
        String lineageId = null;
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            try {
                for (ManifestEntry entry : new ManifestIO().readEntries(snapshots.get(i))) {
                    if (project.equals(entry.project()) && path.equals(entry.path())) {
                        lineageId = entry.lineageId();
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "read " + snapshots.get(i), e);
            }
            if (lineageId != null) {
                break;
            }
        }
        if (lineageId == null) {
            out.println("No history found for " + ref);
            return 0;
        }

        // Second pass: walk oldest first and keep one row per version. A
        // document lies unchanged in most snapshots; only a new
        // body (or a rename) is an event worth a line
        // (planning §4.9).
        List<HistoryRow> rows = new ArrayList<>();
        @Nullable ManifestEntry previous = null;
        boolean inLatest = false;
        for (int i = 0; i < snapshots.size(); i++) {
            Path snap = snapshots.get(i);
            @Nullable ManifestEntry current = null;
            try {
                for (ManifestEntry entry : new ManifestIO().readEntries(snap)) {
                    if (lineageId.equals(entry.lineageId())) {
                        current = entry;
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "read " + snap, e);
            }
            if (i == snapshots.size() - 1) {
                inLatest = current != null;
            }
            if (current == null) {
                continue;
            }
            if (previous == null) {
                rows.add(new HistoryRow(snap.getFileName().toString(), current, "first backup"));
            } else if (contentChanged(previous, current)) {
                rows.add(new HistoryRow(
                        snap.getFileName().toString(),
                        current,
                        previous.path().equals(current.path()) ? "" : "renamed from " + previous.path()));
            } else if (!previous.path().equals(current.path())) {
                rows.add(new HistoryRow(
                        snap.getFileName().toString(), current, "renamed from " + previous.path() + ", same content"));
            }
            previous = current;
        }

        if (rows.isEmpty()) {
            out.println("No history found for " + ref);
            return 0;
        }

        out.println(String.format("%-17s  %9s  %-22s  %s", "CHANGED", "SIZE", "FIRST IN SNAPSHOT", "PATH"));
        out.println("-".repeat(80));
        for (HistoryRow row : rows) {
            String displayPath = row.entry.project() + "/" + row.entry.path();
            out.println(String.format(
                    "%-17s  %9s  %-22s  %s%s",
                    changedAt(row.entry),
                    ListCommand.humanBytes(row.entry.size()),
                    row.snapshotId,
                    displayPath,
                    row.note.isEmpty() ? "" : "  (" + row.note + ")"));
        }
        if (!inLatest) {
            out.println("Not in the latest snapshot — deleted, or excluded by the filter, since then.");
        }
        return 0;
    }

    /** When this version was written: {@code updatedAt}, else {@code createdAt}, in UTC to the minute. */
    /**
     * Whether {@code current} holds a different body than {@code previous}.
     * The hash of the stored bytes answers it exactly when both rows carry
     * one. Otherwise the content version decides where it identifies a body —
     * it is absent in older manifests and on mounted rows, and for an inline
     * document it is the document id, which never changes; there, size and
     * {@code updatedAt} decide.
     */
    static boolean contentChanged(ManifestEntry previous, ManifestEntry current) {
        String previousHash = previous.sha256();
        String currentHash = current.sha256();
        if (previousHash != null && currentHash != null) {
            return !previousHash.equals(currentHash);
        }
        if (!Objects.equals(previous.contentVersion(), current.contentVersion())) {
            return true;
        }
        String version = current.contentVersion();
        if (version != null && !version.equals(current.id())) {
            return false;
        }
        return previous.size() != current.size() || !Objects.equals(previous.updatedAtMs(), current.updatedAtMs());
    }

    private static String changedAt(ManifestEntry entry) {
        Long millis = entry.updatedAtMs() != null ? entry.updatedAtMs() : entry.createdAtMs();
        if (millis == null) {
            return "unknown";
        }
        return CHANGED_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    private static final DateTimeFormatter CHANGED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private record HistoryRow(String snapshotId, ManifestEntry entry, String note) {}
}
