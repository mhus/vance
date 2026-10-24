package de.mhus.vance.liver.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
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
 * Tests for the three manifest-index subcommands: {@code find},
 * {@code history}, {@code extract}. All three are manifest-only
 * (no server, no file content), so the test setup is just two
 * snapshots with synthetic entries.
 */
class ManifestIndexCommandsTest {

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
    void find_matchesPathAcrossSnapshots(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "notes/idee.md", "v1", "lin-1", "Idee"));
        writeSnapshotWithEntry(
                target,
                "2026-10-21T10-30-00Z",
                entry("wiki", "notes/idee.md", "v2", "lin-1", "Idee v2"),
                entry("wiki", "notes/other.md", "v1", "lin-2", "Other"));

        int exit = runFind(home, target, "idee");
        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        // Both idee.md versions show up.
        assertThat(out).contains("idee.md").contains("2026-10-21T10-30-00Z").contains("2026-10-21T09-30-00Z");
    }

    @Test
    void find_matchesTitle(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(
                target, "2026-10-21T09-30-00Z", entry("wiki", "drafts/foo.md", "v1", "lin-1", "Heizung Reparatur"));

        int exit = runFind(home, target, "--title", "Heizung");
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Heizung");
    }

    @Test
    void find_invalidRegex_returnsExit4(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);

        int exit = runFind(home, target, "[unbalanced");
        assertThat(exit).isEqualTo(4);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("Invalid regex");
    }

    @Test
    void find_noMatches_printsFriendlyMessage(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "a.md", "v1", "lin-1", "A"));

        int exit = runFind(home, target, "nonexistent");
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("No matches");
    }

    @Test
    void history_walksLineageAcrossSnapshots(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        // Three versions of the same document (same lineageId), plus
        // one unrelated document in the last snapshot.
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "notes/idee.md", "v1", "lin-1", "Idee"));
        writeSnapshotWithEntry(target, "2026-10-21T10-30-00Z", entry("wiki", "notes/idee.md", "v2", "lin-1", "Idee"));
        writeSnapshotWithEntry(
                target,
                "2026-10-21T11-30-00Z",
                entry("wiki", "notes/idee.md", "v3", "lin-1", "Idee"),
                entry("wiki", "notes/other.md", "v1", "lin-2", "Other"));

        int exit = runHistory(home, target, "wiki/notes/idee.md");
        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        // All three snapshots appear; other.md does not.
        assertThat(out).contains("2026-10-21T09-30-00Z");
        assertThat(out).contains("2026-10-21T10-30-00Z");
        assertThat(out).contains("2026-10-21T11-30-00Z");
        assertThat(out).doesNotContain("other.md");
    }

    @Test
    void history_showsOnlyContentChangesAndRenames(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "notes/a.md", "v1", "lin-1", "A"));
        // Unchanged — must not produce a row.
        writeSnapshotWithEntry(target, "2026-10-21T10-30-00Z", entry("wiki", "notes/a.md", "v1", "lin-1", "A"));
        // Renamed, same content.
        writeSnapshotWithEntry(target, "2026-10-21T11-30-00Z", entry("wiki", "notes/b.md", "v1", "lin-1", "A"));
        // New content.
        writeSnapshotWithEntry(target, "2026-10-21T12-30-00Z", entry("wiki", "notes/b.md", "v2", "lin-1", "A"));
        writeSnapshotWithEntry(target, "2026-10-21T13-30-00Z", entry("wiki", "notes/b.md", "v2", "lin-1", "A"));

        int exit = runHistory(home, target, "wiki/notes/b.md");

        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        assertThat(out)
                .contains("2026-10-21T09-30-00Z")
                .contains("2026-10-21T11-30-00Z")
                .contains("renamed from notes/a.md, same content")
                .contains("2026-10-21T12-30-00Z");
        assertThat(out).doesNotContain("2026-10-21T10-30-00Z").doesNotContain("2026-10-21T13-30-00Z");
        assertThat(out).doesNotContain("Not in the latest snapshot");
    }

    @Test
    void history_documentGoneFromLatest_saysSo(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "notes/a.md", "v1", "lin-1", "A"));
        writeSnapshotWithEntry(target, "2026-10-21T10-30-00Z", entry("wiki", "notes/other.md", "v1", "lin-2", "O"));

        runHistory(home, target, "wiki/notes/a.md");

        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Not in the latest snapshot");
    }

    @Test
    void findDeleted_listsOnlyDocumentsMissingFromTheLatestSnapshot(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(
                target,
                "2026-10-21T09-30-00Z",
                entry("wiki", "notes/gone.md", "v1", "lin-gone", "Gone"),
                entry("wiki", "notes/old-name.md", "v1", "lin-renamed", "Renamed"),
                entry("wiki", "notes/kept.md", "v1", "lin-kept", "Kept"));
        writeSnapshotWithEntry(
                target,
                "2026-10-21T10-30-00Z",
                entry("wiki", "notes/gone.md", "v1", "lin-gone", "Gone"),
                entry("wiki", "notes/new-name.md", "v1", "lin-renamed", "Renamed"),
                entry("wiki", "notes/kept.md", "v1", "lin-kept", "Kept"));
        writeSnapshotWithEntry(
                target,
                "2026-10-21T11-30-00Z",
                entry("wiki", "notes/new-name.md", "v1", "lin-renamed", "Renamed"),
                entry("wiki", "notes/kept.md", "v1", "lin-kept", "Kept"));

        int exit = runFind(home, target, "--deleted", "notes");

        assertThat(exit).isEqualTo(0);
        String out = capturedOut.toString(StandardCharsets.UTF_8);
        // Once, from the newest snapshot that still has it.
        assertThat(out.lines().filter(l -> l.contains("gone.md")).toList())
                .containsExactly("2026-10-21T10-30-00Z\twiki/notes/gone.md\tGone");
        // A rename is not a deletion; a kept document is not either.
        assertThat(out).doesNotContain("old-name.md").doesNotContain("kept.md");
    }

    @Test
    void history_unknownDocument_returnsZero(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Files.createDirectories(target);

        int exit = runHistory(home, target, "wiki/nonexistent.md");
        assertThat(exit).isEqualTo(0);
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("No history");
    }

    @Test
    void history_invalidRef_returnsExit4(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        int exit = runHistory(home, target, "noSlash");
        assertThat(exit).isEqualTo(4);
    }

    @Test
    void extract_copiesLatestVersion(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshotDir = writeSnapshotWithEntry(
                target, "2026-10-21T09-30-00Z", entry("wiki", "notes/idee.md", "v1", "lin-1", "Idee"));
        Files.writeString(snapshotDir.resolve("projects/wiki/notes/idee.md"), "Hello");

        Path outDir = home.resolve("out");
        int exit = runExtract(home, target, "wiki/notes/idee.md", "--to", outDir.toString());
        assertThat(exit).isEqualTo(0);

        // The output file uses the title as the filename.
        Path extracted = outDir.resolve("Idee");
        assertThat(extracted).hasContent("Hello");
    }

    @Test
    void extract_alreadyExists_addsSnapshotSuffix(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snapshotDir = writeSnapshotWithEntry(
                target, "2026-10-21T09-30-00Z", entry("wiki", "notes/idee.md", "v1", "lin-1", "Idee"));
        Files.writeString(snapshotDir.resolve("projects/wiki/notes/idee.md"), "v1-content");

        Path outDir = home.resolve("out");
        // First extract: creates Idee.
        runExtract(home, target, "wiki/notes/idee.md", "--to", outDir.toString());
        // Second extract: should NOT overwrite; creates Idee@2026-10-21T09-30-00Z.
        runExtract(home, target, "wiki/notes/idee.md", "--to", outDir.toString());

        assertThat(outDir.resolve("Idee")).hasContent("v1-content");
        assertThat(outDir.resolve("Idee@2026-10-21T09-30-00Z")).hasContent("v1-content");
    }

    @Test
    void extract_atSpecificSnapshot(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        Path snap1 = writeSnapshotWithEntry(
                target, "2026-10-21T09-30-00Z", entry("wiki", "notes/idee.md", "v1", "lin-1", "Idee"));
        Files.writeString(snap1.resolve("projects/wiki/notes/idee.md"), "v1");
        Path snap2 = writeSnapshotWithEntry(
                target, "2026-10-21T10-30-00Z", entry("wiki", "notes/idee.md", "v2", "lin-1", "Idee"));
        Files.writeString(snap2.resolve("projects/wiki/notes/idee.md"), "v2");

        Path outDir = home.resolve("out");
        int exit = runExtract(
                home, target, "wiki/notes/idee.md", "--at", "2026-10-21T09-30-00Z", "--to", outDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(outDir.resolve("Idee")).hasContent("v1");
    }

    @Test
    void extract_unknownDocument_returnsExit2(@TempDir Path home) throws IOException {
        Path target = home.resolve("snapshots");
        writeSnapshotWithEntry(target, "2026-10-21T09-30-00Z", entry("wiki", "a.md", "v1", "lin-1", "A"));

        Path outDir = home.resolve("out");
        int exit = runExtract(home, target, "wiki/nonexistent.md", "--to", outDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    // -- helpers --------------------------------------------------------------

    private static ManifestEntry entry(String project, String path, String version, String lineage, String title) {
        return new ManifestEntry(
                project,
                path,
                "projects/" + project + "/" + path,
                "id-" + path,
                lineage,
                "markdown",
                title,
                "text/markdown",
                100L,
                1700000000000L,
                1700000000000L,
                version,
                false,
                // One body per version — the hash follows the content.
                "sha-" + version,
                ManifestEntry.Stored.DOWNLOADED);
    }

    private static Path writeSnapshotWithEntry(Path target, String id, ManifestEntry... entries) throws IOException {
        Files.createDirectories(target);
        Path snapshot = target.resolve(id);
        Files.createDirectories(snapshot);
        Path parent = snapshot.resolve("projects");
        ManifestIO io = new ManifestIO();
        for (ManifestEntry e : entries) {
            Files.createDirectories(snapshot.resolve(e.file()).getParent());
            io.appendEntry(snapshot, e);
        }
        return snapshot;
    }

    private int runFind(Path home, Path target, String... extra) {
        Path config = writeConfig(home, target);
        List<String> args = new java.util.ArrayList<>();
        args.add("--config");
        args.add(config.toString());
        args.add("--path");
        for (String s : extra) {
            args.add(s);
        }
        return new CommandLine(new FindCommand()).execute(args.toArray(new String[0]));
    }

    private int runHistory(Path home, Path target, String ref) {
        Path config = writeConfig(home, target);
        return new CommandLine(new HistoryCommand()).execute("--config", config.toString(), ref);
    }

    private int runExtract(Path home, Path target, String ref, String... extra) {
        Path config = writeConfig(home, target);
        List<String> args = new java.util.ArrayList<>();
        args.add("--config");
        args.add(config.toString());
        args.add(ref);
        for (String s : extra) {
            args.add(s);
        }
        return new CommandLine(new ExtractCommand()).execute(args.toArray(new String[0]));
    }

    private Path writeConfig(Path home, Path target) {
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
        return config;
    }
}
