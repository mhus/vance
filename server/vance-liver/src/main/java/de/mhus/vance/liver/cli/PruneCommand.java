package de.mhus.vance.liver.cli;

import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup prune} — Time-Machine-style
 * retention (planning §4.8).
 *
 * <p>Given the retention buckets (hourly / daily / weekly) from the
 * config, picks which snapshots to keep:
 *
 * <pre>
 *   - every snapshot in the last 24 hours
 *   - one snapshot per day for the next 30 days
 *   - one snapshot per week for the next 52 weeks
 * </pre>
 *
 * <p>Snapshots are identified by their id
 * ({@code yyyy-MM-ddTHH-mm-ss'Z'}) which is also the directory name
 * under the target root.
 *
 * <p>Files shared between snapshots via hardlinks are unaffected —
 * deleting one snapshot's directory only removes the directory
 * entry, not the inode. Files referenced from other surviving
 * snapshots stay alive.
 */
@Command(
        name = "prune",
        mixinStandardHelpOptions = true,
        description = "Apply Time-Machine-style retention to local snapshots.")
@NullMarked
public final class PruneCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    @Option(
            names = {"--dry-run", "-n"},
            description = "List the snapshots that would be removed, but do not delete them.")
    private boolean dryRun;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

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
            out.println("Nothing to prune — target " + target + " does not exist.");
            return 0;
        }

        List<SnapshotRef> snapshots;
        try {
            snapshots = listSnapshots(target);
        } catch (IOException e) {
            err.println("Cannot list snapshots: " + e.getMessage());
            return 2;
        }
        if (snapshots.isEmpty()) {
            out.println("Nothing to prune.");
            return 0;
        }

        // Sort newest first.
        snapshots.sort(Comparator.comparing((SnapshotRef s) -> s.timestamp).reversed());

        List<SnapshotRef> keep =
                pickKeepers(snapshots, cfg.retention.hourly, cfg.retention.daily, cfg.retention.weekly);
        List<SnapshotRef> drop = new ArrayList<>(snapshots);
        drop.removeAll(keep);

        out.println("Snapshots total : " + snapshots.size());
        out.println("Snapshots kept  : " + keep.size());
        out.println("Snapshots pruned: " + drop.size());

        if (drop.isEmpty()) {
            if (dryRun) {
                out.println();
                out.println("Dry run — nothing to prune.");
            }
            return 0;
        }

        if (dryRun) {
            out.println();
            out.println("Dry run — would remove:");
            for (SnapshotRef s : drop) {
                out.println("  " + s.id);
            }
            return 0;
        }

        int errors = 0;
        for (SnapshotRef s : drop) {
            try {
                deleteSnapshot(s.dir);
                out.println("Removed: " + s.id);
            } catch (IOException e) {
                err.println("Failed to remove " + s.id + ": " + e.getMessage());
                errors++;
            }
        }

        // If we removed the LATEST one, point LATEST at the newest survivor.
        try {
            @Nullable Path latest = newest(keep);
            if (latest != null) {
                Files.writeString(target.resolve("LATEST"), latest.getFileName().toString());
            } else {
                Files.deleteIfExists(target.resolve("LATEST"));
            }
            // Always remove the .lock if it lingered — prune doesn't run a backup.
            Files.deleteIfExists(target.resolve(".lock"));
        } catch (IOException e) {
            err.println("Could not update LATEST marker: " + e.getMessage());
            errors++;
        }

        return errors == 0 ? 0 : 2;
    }

    /**
     * Apply the Time-Machine buckets. Newest first — the first N
     * hourly snapshots stay, then one per day for the next 30 days
     * (excluding days already covered by hourly), then one per week
     * for the next 52 weeks (excluding weeks already covered).
     */
    static List<SnapshotRef> pickKeepers(List<SnapshotRef> snapshotsNewestFirst, int hourly, int daily, int weekly) {
        List<SnapshotRef> keep = new ArrayList<>();
        Instant now = Instant.now();

        // Track which entries the hourly bucket already covers so
        // the daily/weekly buckets can skip them.
        java.util.Set<java.time.LocalDate> coveredDays = new java.util.HashSet<>();
        java.util.Set<String> coveredWeeks = new java.util.HashSet<>();

        // Hourly bucket: keep the first `hourly` snapshots within
        // the last 24 hours. Anything older falls through to the
        // daily/weekly buckets — counting them here would keep
        // snapshots the retention policy says to drop.
        for (int i = 0; i < snapshotsNewestFirst.size() && i < hourly; i++) {
            SnapshotRef s = snapshotsNewestFirst.get(i);
            long hoursAgo = Duration.between(s.timestamp, now).toHours();
            if (hoursAgo >= 24) {
                break;
            }
            keep.add(s);
            java.time.ZonedDateTime zdt = s.timestamp.atZone(ZoneOffset.UTC);
            coveredDays.add(zdt.toLocalDate());
            coveredWeeks.add(zdt.getYear() + "-W" + zdt.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
        }

        // Daily bucket: for each of the last `daily` days (not yet
        // covered), keep the newest snapshot of that day.
        java.util.Set<java.time.LocalDate> keptDays = new java.util.HashSet<>();
        for (SnapshotRef s : snapshotsNewestFirst) {
            java.time.LocalDate day = s.timestamp.atZone(ZoneOffset.UTC).toLocalDate();
            if (coveredDays.contains(day)) {
                continue;
            }
            long daysAgo = Duration.between(day.atStartOfDay().toInstant(ZoneOffset.UTC), now)
                    .toDays();
            if (daysAgo < 1 || daysAgo >= daily) {
                continue;
            }
            if (keptDays.add(day)) {
                keep.add(s);
            }
        }

        // Weekly bucket: ISO-week buckets, one snapshot per week for
        // the last `weekly` weeks (not already covered).
        java.util.Set<String> keptWeeks = new java.util.HashSet<>();
        for (SnapshotRef s : snapshotsNewestFirst) {
            java.time.ZonedDateTime zdt = s.timestamp.atZone(ZoneOffset.UTC);
            String week = zdt.getYear() + "-W" + zdt.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            if (coveredWeeks.contains(week)) {
                continue;
            }
            long weeksAgo = Duration.between(zdt.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC), now)
                            .toDays()
                    / 7;
            if (weeksAgo < 1 || weeksAgo >= weekly) {
                continue;
            }
            if (keptWeeks.add(week)) {
                keep.add(s);
            }
        }

        return keep;
    }

    private static @Nullable Path newest(List<SnapshotRef> keep) {
        return keep.stream()
                .max(Comparator.comparing((SnapshotRef s) -> s.timestamp))
                .map(s -> s.dir)
                .orElse(null);
    }

    private static void deleteSnapshot(Path snapshot) throws IOException {
        // Walk and delete. We don't try to remove the LATEST file —
        // the caller handles that separately.
        try (var stream = Files.walk(snapshot)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private static List<SnapshotRef> listSnapshots(Path target) throws IOException {
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);
        List<SnapshotRef> result = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        try {
                            Instant ts = Instant.from(formatter.parse(name));
                            result.add(new SnapshotRef(name, p, ts));
                        } catch (java.time.format.DateTimeParseException e) {
                            // Not a snapshot — directory is something else, skip.
                        }
                    });
        }
        return result;
    }

    /** Holds a snapshot's id, directory, and parsed timestamp. */
    record SnapshotRef(String id, Path dir, Instant timestamp) {}

    /** Suppress unused warning on @Option import. */
    @SuppressWarnings("unused")
    private static @Nullable Object noopForImportAnchor() {
        return null;
    }
}
