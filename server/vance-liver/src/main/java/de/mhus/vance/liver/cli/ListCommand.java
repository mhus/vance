package de.mhus.vance.liver.cli;

import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import de.mhus.vance.liver.snapshot.SnapshotWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup list}.
 *
 * <p>Lists the snapshots under the configured target directory, newest
 * first. Each row carries the snapshot id (which doubles as a directory
 * name), when it was started and finished, the document count, the
 * bytes downloaded, and the filter that produced it
 * ({@code planning/user-backup.md} §4.2).
 *
 * <p>Snapshots with no {@code manifest.json} (incomplete runs,
 * manual surgery) are listed with a question mark so the operator
 * notices them.
 */
@Command(name = "list", mixinStandardHelpOptions = true, description = "List local snapshots, newest first.")
@NullMarked
public final class ListCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(ListCommand.class.getName());

    @Override
    public Integer call() {
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }

        Path target = Path.of(cfg.target);
        if (!Files.exists(target)) {
            out.println("No snapshots yet — target directory " + target + " does not exist.");
            return 0;
        }

        List<SnapshotRow> rows = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                            .reversed())
                    .forEach(p -> rows.add(loadRow(p)));
        } catch (java.io.IOException e) {
            err.println("Cannot list " + target + ": " + e.getMessage());
            return 2;
        }

        if (rows.isEmpty()) {
            out.println("No snapshots under " + target);
            return 0;
        }

        out.println(
                String.format("%-26s  %8s  %10s  %10s  %-8s  %s", "ID", "DOCS", "BYTES", "LINKED", "FAILED", "WHEN"));
        out.println("-".repeat(82));
        for (SnapshotRow row : rows) {
            String id = row.id;
            String docs = row.docs == null ? "?" : String.valueOf(row.docs);
            String bytes = row.bytes == null ? "?" : humanBytes(row.bytes);
            String linked = row.linked == null ? "?" : String.valueOf(row.linked);
            String failed = row.failed == null ? "?" : String.valueOf(row.failed);
            String when = row.startedAt == null ? "?" : row.startedAt;
            out.println(String.format("%-26s  %8s  %10s  %10s  %8s  %s", id, docs, bytes, linked, failed, when));
        }

        Path latest = SnapshotWriter.latestSnapshot(target);
        if (latest != null) {
            out.println();
            out.println("LATEST: " + latest.getFileName());
        }

        return 0;
    }

    private static SnapshotRow loadRow(Path snapshotDir) {
        SnapshotRow row = new SnapshotRow();
        row.id = snapshotDir.getFileName().toString();
        Path manifestFile = snapshotDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            return row;
        }
        try {
            SnapshotManifest m = new ManifestIO().readManifest(snapshotDir);
            row.startedAt = m.startedAt();
            row.docs = m.counts().documents();
            row.bytes = m.bytes().downloaded();
            row.linked = m.counts().linked();
            row.failed = m.counts().failed();
        } catch (java.io.IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read manifest in " + snapshotDir + ": " + e.getMessage());
        }
        return row;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fM", bytes / (1024.0 * 1024));
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }

    private static final class SnapshotRow {
        @Nullable
        String id;

        @Nullable
        String startedAt;

        @Nullable
        Long docs;

        @Nullable
        Long bytes;

        @Nullable
        Long linked;

        @Nullable
        Long failed;
    }
}
