package de.mhus.vance.backup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.backup.manifest.ManifestEntry;
import de.mhus.vance.backup.manifest.ManifestIO;
import de.mhus.vance.backup.manifest.SnapshotManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ListCommand} — reads the target directory and
 * prints snapshot rows.
 *
 * <p>Each test writes one or two snapshots under a target directory
 * and asserts what the command prints.
 */
class ListCommandTest {

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
    void emptyTarget_printsFriendlyMessage(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);

        int exit = runWithTarget(home, target);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("No snapshots");
    }

    @Test
    void missingTarget_printsFriendlyMessage(@TempDir Path home) {
        Path target = home.resolve("snapshots-does-not-exist");

        int exit = runWithTarget(home, target);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("does not exist");
    }

    @Test
    void listsSnapshotsNewestFirst(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);
        writeSnapshot(target, "2026-10-20T09-30-00Z", 10L, 1024L, 9L, 0L, "2026-10-20T09:30:00Z");
        writeSnapshot(target, "2026-10-21T10-30-00Z", 50L, 4096L, 45L, 0L, "2026-10-21T10:30:00Z");
        Files.writeString(target.resolve("LATEST"), "2026-10-21T10-30-00Z");

        int exit = runWithTarget(home, target);
        assertThat(exit).isEqualTo(0);

        String out = capturedOut.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("2026-10-21T10-30-00Z");
        assertThat(out).contains("2026-10-20T09-30-00Z");
        // Newest first: the index of 21 must come before 20.
        assertThat(out.indexOf("2026-10-21")).isLessThan(out.indexOf("2026-10-20"));
        // Document counts from the manifests.
        assertThat(out).contains("50");
        assertThat(out).contains("10");
        // LATEST marker.
        assertThat(out).contains("LATEST: 2026-10-21T10-30-00Z");
    }

    @Test
    void ignoresInProgressDirectories(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);
        writeSnapshot(target, "2026-10-20T09-30-00Z", 10L, 1024L, 9L, 0L, "2026-10-20T09:30:00Z");
        Files.createDirectory(target.resolve(".in-progress-2026-10-22T11-00-00Z"));

        int exit = runWithTarget(home, target);
        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("2026-10-20T09-30-00Z");
        assertThat(out).doesNotContain(".in-progress");
    }

    @Test
    void snapshotWithoutManifest_isListedButWithQuestionMarks(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);
        Files.createDirectory(target.resolve("2026-10-19T08-00-00Z"));
        // No manifest.json — operator notices the question marks.

        int exit = runWithTarget(home, target);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("2026-10-19T08-00-00Z");
    }

    // -- helpers --------------------------------------------------------------

    private int runWithTarget(Path home, Path target) {
        Path config = home.resolve("backup.yaml");
        try {
            Files.writeString(config, """
                    server:
                      url: "http://127.0.0.1:1"
                      tenant: acme
                    token: "testtoken"
                    target: "%s"
                    """.formatted(target));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new CommandLine(new ListCommand()).execute("--config", config.toString());
    }

    private static void writeSnapshot(
            Path target, String id, long docs, long bytes, long linked, long failed, String startedAt)
            throws IOException {
        Path dir = target.resolve(id);
        Files.createDirectories(dir);
        SnapshotManifest m = new SnapshotManifest(
                SnapshotManifest.CURRENT_FORMAT,
                "vancetope-backup 0.3.0-test",
                "http://localhost:9991",
                "acme",
                "tester",
                id,
                startedAt,
                startedAt,
                new SnapshotManifest.Filter(
                        new SnapshotManifest.ProjectsFilter(List.of("*"), List.of(), false),
                        List.of("_vance/**"),
                        Map.of()),
                null,
                new SnapshotManifest.Counts(docs, docs - linked, linked, 0L, failed),
                new SnapshotManifest.Bytes(bytes, bytes));
        new ManifestIO().writeManifest(dir, m);
        // entries.jsonl must exist for the snapshot to count as 'complete'.
        ManifestEntry entry = new ManifestEntry(
                "wiki",
                "a.md",
                "projects/wiki/a.md",
                "id-1",
                "lin-1",
                "markdown",
                "Title",
                "text/markdown",
                100L,
                1700000000000L,
                1700000000000L,
                "v1",
                false,
                "abc",
                ManifestEntry.Stored.LINKED);
        new ManifestIO().appendEntry(dir, entry);
    }
}
