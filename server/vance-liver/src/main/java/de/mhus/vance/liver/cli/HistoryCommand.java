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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup history <project>/<path>}.
 *
 * <p>Walks every snapshot under the target and prints every entry
 * whose {@code lineageId} matches the latest-known lineage of
 * {@code <project>/<path>}. This shows the version history of one
 * document across all snapshots, oldest first.
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

        // Second pass: walk oldest first, collect entries with that lineageId.
        List<HistoryRow> rows = new ArrayList<>();
        for (Path snap : snapshots) {
            try {
                for (ManifestEntry entry : new ManifestIO().readEntries(snap)) {
                    if (lineageId.equals(entry.lineageId())) {
                        rows.add(new HistoryRow(snap.getFileName().toString(), entry));
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "read " + snap, e);
            }
        }

        if (rows.isEmpty()) {
            out.println("No history found for " + ref);
            return 0;
        }

        out.println(String.format("%-26s  %-10s  %s", "SNAPSHOT", "WHEN", "PATH"));
        out.println("-".repeat(80));
        for (HistoryRow row : rows) {
            String when = "unknown";
            try {
                when = Instant.parse(
                                row.entry.createdAtMs() != null
                                        ? Instant.ofEpochMilli(row.entry.createdAtMs())
                                                .toString()
                                        : Instant.parse(row.snapshotId.substring(0, 19) + "Z")
                                                .toString())
                        .toString();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            String displayPath = row.entry.project() + "/" + row.entry.path();
            out.println(String.format(
                    "%-26s  %-10s  %s",
                    row.snapshotId, when.length() >= 10 ? when.substring(0, 10) : when, displayPath));
        }
        return 0;
    }

    private record HistoryRow(String snapshotId, ManifestEntry entry) {}
}
