package de.mhus.vance.backup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.backup.manifest.ManifestEntry;
import de.mhus.vance.backup.manifest.ManifestIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VerifyCommand} — re-hashes a snapshot and
 * compares to the manifest. Three snapshots cover the three states:
 * clean, mismatch, missing.
 */
class VerifyCommandTest {

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
    void cleanSnapshot_returnsZero(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshot = writeSnapshotWithFile(target, "2026-10-21T09-30-00Z", "hello");

        int exit = runVerify(home, target, "2026-10-21T09-30-00Z");
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8))
                .contains("OK       : 1")
                .contains("Failed   : 0");
    }

    @Test
    void mismatch_returnsOneAndReports(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshot = writeSnapshotWithFile(target, "2026-10-21T09-30-00Z", "original");
        // Corrupt the file after writing — manifest still says sha256(original).
        Files.writeString(snapshot.resolve("projects/wiki/a.md"), "tampered");

        int exit = runVerify(home, target, "2026-10-21T09-30-00Z");
        assertThat(exit).isEqualTo(1);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("MISMATCH")
                .contains("wiki/a.md");
    }

    @Test
    void missingFile_returnsOne(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshot = writeSnapshotWithFile(target, "2026-10-21T09-30-00Z", "hello");
        Files.delete(snapshot.resolve("projects/wiki/a.md"));

        int exit = runVerify(home, target, "2026-10-21T09-30-00Z");
        assertThat(exit).isEqualTo(1);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("MISSING")
                .contains("wiki/a.md");
    }

    @Test
    void latestSnapshot_defaultArgument(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithFile(target, "2026-10-21T09-30-00Z", "first");
        writeSnapshotWithFile(target, "2026-10-21T10-30-00Z", "second");
        Files.writeString(target.resolve("LATEST"), "2026-10-21T10-30-00Z");

        int exit = runVerify(home, target, null);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("2026-10-21T10-30-00Z");
    }

    @Test
    void failedEntries_areSkipped(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshot = target.resolve("2026-10-21T09-30-00Z");
        Files.createDirectories(snapshot.resolve("projects/wiki"));
        // Entry with stored=FAILED — must be skipped, not reported as missing.
        ManifestEntry failed = new ManifestEntry(
                "wiki",
                "a.md",
                "projects/wiki/a.md",
                "id-1",
                "lin-1",
                "markdown",
                "Title",
                "text/markdown",
                100L,
                0L,
                0L,
                "v1",
                false,
                null,
                ManifestEntry.Stored.FAILED);
        new ManifestIO().appendEntry(snapshot, failed);
        Files.writeString(target.resolve("LATEST"), "2026-10-21T09-30-00Z");

        int exit = runVerify(home, target, null);
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Skipped  : 1");
    }

    @Test
    void missingConfig_returnsExit4(@TempDir Path home) {
        int exit = new CommandLine(new VerifyCommand())
                .execute("--config", home.resolve("does-not-exist.yaml").toString());
        assertThat(exit).isEqualTo(4);
    }

    @Test
    void unknownSnapshotId_returnsExit4(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);
        Files.writeString(home.resolve("backup.yaml"), """
                server:
                  url: "http://x"
                  tenant: acme
                token: "x"
                target: "%s"
                """.formatted(target));
        int exit = new CommandLine(new VerifyCommand())
                .execute("--config", home.resolve("backup.yaml").toString(), "--snapshot", "1970-01-01T00-00-00Z");
        assertThat(exit).isEqualTo(4);
    }

    // -- helpers --------------------------------------------------------------

    private static Path writeSnapshotWithFile(Path target, String id, String content) throws IOException {
        Files.createDirectories(target);
        Path snapshot = target.resolve(id);
        Path file = snapshot.resolve("projects/wiki/a.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        // sha256 of the content.
        String sha = sha256(content);
        ManifestEntry entry = new ManifestEntry(
                "wiki",
                "a.md",
                "projects/wiki/a.md",
                "id-1",
                "lin-1",
                "markdown",
                "Title",
                "text/markdown",
                (long) content.length(),
                0L,
                0L,
                "v1",
                false,
                sha,
                ManifestEntry.Stored.DOWNLOADED);
        new ManifestIO().appendEntry(snapshot, entry);
        return snapshot;
    }

    private static String sha256(String s) {
        try {
            var d = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private int runVerify(Path home, Path target, @org.jspecify.annotations.Nullable String snapshot) {
        Path config = home.resolve("backup.yaml");
        try {
            Files.writeString(config, """
                    server:
                      url: "http://x"
                      tenant: acme
                    token: "x"
                    target: "%s"
                    """.formatted(target));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<String> args = new java.util.ArrayList<>(List.of("--config", config.toString()));
        if (snapshot != null) {
            args.add("--snapshot");
            args.add(snapshot);
        }
        return new CommandLine(new VerifyCommand()).execute(args.toArray(new String[0]));
    }
}
