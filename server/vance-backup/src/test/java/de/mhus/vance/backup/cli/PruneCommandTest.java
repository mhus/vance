package de.mhus.vance.backup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.backup.cli.PruneCommand.SnapshotRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link PruneCommand} — the Time-Machine retention
 * bucket logic and the file-system side.
 *
 * <p>{@link PruneCommand#pickKeepers} is the algorithmic core; the
 * rest is mostly file I/O. The unit tests cover pickKeepers
 * directly (deterministic), and one end-to-end test exercises the
 * file deletion + LATEST update.
 */
class PruneCommandTest {

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void pickKeepers_keepsFirstHourlyBucket() {
        // Twenty-four snapshots, one per hour. All within 24h.
        Instant now = Instant.now();
        List<SnapshotRef> snapshots = hourlySnapshots(now, 24);

        List<SnapshotRef> kept = PruneCommand.pickKeepers(snapshots, 24, 30, 52);

        assertThat(kept).hasSize(24);
    }

    @Test
    void pickKeepers_keepsOnePerDayAcrossADayBoundary() {
        // 26 snapshots: 24 hourly (covering the last ~24h, which may
        // spill into 'yesterday UTC'), plus one from two days ago
        // (outside the hourly coverage) and one from three days ago.
        // Both should land in the daily bucket.
        Instant now = Instant.now();
        List<SnapshotRef> snapshots = hourlySnapshots(now, 24);
        snapshots.add(snapshotAt(idAt(now.minus(Duration.ofDays(2))), now.minus(Duration.ofDays(2))));
        snapshots.add(snapshotAt(idAt(now.minus(Duration.ofDays(3))), now.minus(Duration.ofDays(3))));

        List<SnapshotRef> kept = PruneCommand.pickKeepers(snapshots, 24, 30, 52);

        // 24 hourly + 2 daily snapshots from prior days = 26.
        assertThat(kept).hasSize(26);
    }

    @Test
    void pickKeepers_dropsEverythingBeyondRetention() {
        Instant now = Instant.now();
        // Snapshots 5 years apart — comfortably beyond the daily+weekly
        // horizon of (30 days, 52 weeks). All should be dropped.
        // The list is sorted newest-first as the caller must do.
        List<SnapshotRef> snapshots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Newest first: 5 years ago, then 10, 15, 20, 25.
            Instant t = now.minus(Duration.ofDays(365L * 5 * (i + 1)));
            snapshots.add(snapshotAt(idAt(t), t));
        }

        List<SnapshotRef> kept = PruneCommand.pickKeepers(snapshots, 24, 30, 52);

        assertThat(kept).isEmpty();
    }

    @Test
    void pickKeepers_keepsOnePerWeekAcrossMany() {
        Instant now = Instant.now();
        // Eight snapshots, one per week for the last 8 weeks.
        // All inside the weekly bucket (52 weeks).
        List<SnapshotRef> snapshots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Instant t = now.minus(Duration.ofDays(7L * i + 30));
            snapshots.add(snapshotAt(idAt(t), t));
        }

        List<SnapshotRef> kept = PruneCommand.pickKeepers(snapshots, 24, 30, 52);

        assertThat(kept).hasSize(8);
    }

    @Test
    void dryRun_listsButDoesNotDelete(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeDummySnapshot(target, "2026-10-21T09-30-00Z");
        writeDummySnapshot(target, "2026-10-21T10-30-00Z");
        writeDummySnapshot(target, "2026-10-21T11-30-00Z");
        Files.writeString(target.resolve("LATEST"), "2026-10-21T11-30-00Z");

        int exit = runPrune(home, target, true);
        assertThat(exit).isEqualTo(0);

        // No snapshots removed.
        long count = Files.list(target)
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .count();
        assertThat(count).isEqualTo(3);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Dry run");
    }

    @Test
    void pruneRemovesExcessAndUpdatesLatest(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        // 30 snapshots in the last 30 days. hourly=24, daily=30,
        // weekly=52 → all 30 should be kept.
        for (int i = 0; i < 30; i++) {
            writeDummySnapshot(target, idAt(Instant.now().minus(Duration.ofDays(i))));
        }
        Files.writeString(
                target.resolve("LATEST"),
                Files.list(target)
                        .filter(Files::isDirectory)
                        .max(Comparator.comparing(p -> p.getFileName().toString()))
                        .orElseThrow()
                        .getFileName()
                        .toString());

        int exit = runPrune(home, target, false);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Snapshots pruned: 0");
    }

    @Test
    void missingConfig_returnsExit4(@TempDir Path home) {
        int exit = new CommandLine(new PruneCommand())
                .execute("--config", home.resolve("does-not-exist.yaml").toString());
        assertThat(exit).isEqualTo(4);
    }

    @Test
    void missingTarget_returnsZero(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots-does-not-exist");
        int exit = runPrune(home, target, false);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("does not exist");
    }

    // -- helpers --------------------------------------------------------------

    private int runPrune(Path home, Path target, boolean dryRun) throws IOException {
        Files.writeString(home.resolve("backup.yaml"), """
                server:
                  url: "http://x"
                  tenant: acme
                token: "x"
                target: "%s"
                """.formatted(target));
        List<String> args = new java.util.ArrayList<>(
                List.of("--config", home.resolve("backup.yaml").toString()));
        if (dryRun) {
            args.add("--dry-run");
        }
        return new CommandLine(new PruneCommand()).execute(args.toArray(new String[0]));
    }

    private static void writeDummySnapshot(Path target, String id) throws IOException {
        Path dir = target.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{}");
    }

    private static List<SnapshotRef> hourlySnapshots(Instant anchor, int n) {
        DateTimeFormatter f =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);
        List<SnapshotRef> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant t = anchor.minus(Duration.ofHours(i));
            String id = f.format(t);
            out.add(new SnapshotRef(id, Path.of("/tmp/fake/" + id), t));
        }
        return out;
    }

    private static SnapshotRef snapshotAt(String id, Instant t) {
        return new SnapshotRef(id, Path.of("/tmp/fake/" + id), t);
    }

    private static String idAt(Instant t) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(t);
    }
}
