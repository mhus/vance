package de.mhus.vance.liver.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ManifestIO} — the round-trip from the in-memory
 * manifest shape to the on-disk JSON.
 *
 * <p>The manifest format is local to the tool; these tests pin the
 * JSON shape so a future refactor cannot quietly rename a field.
 */
class ManifestIOTest {

    private final ManifestIO io = new ManifestIO();

    @Test
    void manifest_roundTrips(@TempDir Path tmp) throws IOException {
        SnapshotManifest manifest = sampleManifest();
        io.writeManifest(tmp, manifest);
        SnapshotManifest back = io.readManifest(tmp);

        assertThat(back.format()).isEqualTo(manifest.format());
        assertThat(back.tool()).isEqualTo(manifest.tool());
        assertThat(back.server()).isEqualTo(manifest.server());
        assertThat(back.tenant()).isEqualTo(manifest.tenant());
        assertThat(back.snapshotId()).isEqualTo(manifest.snapshotId());
        assertThat(back.counts()).isEqualTo(manifest.counts());
        assertThat(back.bytes()).isEqualTo(manifest.bytes());
        assertThat(back.filter().projects().include()).containsExactly("*");
        assertThat(back.filter().deny()).containsExactly("_vance/**");
        assertThat(back.filter().projectDeny()).containsKey("wiki");
    }

    @Test
    void entries_onePerLine(@TempDir Path tmp) throws IOException {
        for (int i = 0; i < 5; i++) {
            io.appendEntry(tmp, sampleEntry("wiki", "notes/idee" + i + ".md", ManifestEntry.Stored.DOWNLOADED));
        }

        // Raw file: each entry on its own line.
        List<String> lines = Files.readAllLines(tmp.resolve("entries.jsonl"));
        assertThat(lines).hasSize(5);
        for (String line : lines) {
            // Each line must be a self-contained JSON object.
            assertThat(line).startsWith("{");
            assertThat(line).endsWith("}");
        }
    }

    @Test
    void entries_roundTrip(@TempDir Path tmp) throws IOException {
        io.appendEntry(tmp, sampleEntry("wiki", "notes/a.md", ManifestEntry.Stored.DOWNLOADED));
        io.appendEntry(tmp, sampleEntry("wiki", "notes/b.md", ManifestEntry.Stored.LINKED));
        io.appendEntry(tmp, sampleEntry("journal", "2026/10.md", ManifestEntry.Stored.COPIED));
        io.appendEntry(tmp, sampleEntry("wiki", "broken.md", ManifestEntry.Stored.FAILED));

        List<ManifestEntry> back = io.readEntries(tmp);
        assertThat(back).hasSize(4);
        assertThat(back.get(0).path()).isEqualTo("notes/a.md");
        assertThat(back.get(0).stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
        assertThat(back.get(1).stored()).isEqualTo(ManifestEntry.Stored.LINKED);
        assertThat(back.get(2).stored()).isEqualTo(ManifestEntry.Stored.COPIED);
        assertThat(back.get(3).stored()).isEqualTo(ManifestEntry.Stored.FAILED);
    }

    @Test
    void entries_blankLines_areSkipped(@TempDir Path tmp) throws IOException {
        io.appendEntry(tmp, sampleEntry("wiki", "a.md", ManifestEntry.Stored.LINKED));
        // Inject a blank line in the middle — survives crashes.
        Files.writeString(tmp.resolve("entries.jsonl"), Files.readString(tmp.resolve("entries.jsonl")) + "\n\n");
        io.appendEntry(tmp, sampleEntry("wiki", "b.md", ManifestEntry.Stored.LINKED));

        List<ManifestEntry> back = io.readEntries(tmp);
        assertThat(back).hasSize(2);
    }

    @Test
    void readEntries_missingFile_returnsEmptyList(@TempDir Path tmp) throws IOException {
        // No entries.jsonl, no exception — readers don't have to
        // special-case the empty case.
        assertThat(io.readEntries(tmp)).isEmpty();
    }

    @Test
    void readManifest_missingFile_throws(@TempDir Path tmp) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> io.readManifest(tmp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("manifest.json");
    }

    @Test
    void storedEnum_allFourValuesParse() throws IOException {
        // The four states must all survive a round-trip — a future
        // refactor that renames one of them would silently break
        // older manifests.
        for (ManifestEntry.Stored s : ManifestEntry.Stored.values()) {
            ManifestEntry e = sampleEntry("p", "f", s);
            Path tmp = Files.createTempDirectory("liver-test");
            try {
                io.appendEntry(tmp, e);
                List<ManifestEntry> back = io.readEntries(tmp);
                assertThat(back).hasSize(1);
                assertThat(back.get(0).stored()).isEqualTo(s);
            } finally {
                // Best-effort cleanup; TempDir is for tests with the
                // annotation, this helper avoids the boilerplate.
                Files.walk(tmp)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    // -- helpers --------------------------------------------------------------

    private static SnapshotManifest sampleManifest() {
        return new SnapshotManifest(
                SnapshotManifest.CURRENT_FORMAT,
                "vancetope-backup 0.3.0",
                "https://brain.example",
                "acme",
                "road.runner",
                "2026-10-21T09-30-00Z",
                "2026-10-21T09:30:00Z",
                "2026-10-21T09:31:12Z",
                new SnapshotManifest.Filter(
                        new SnapshotManifest.ProjectsFilter(List.of("*"), List.of(), false),
                        List.of("_vance/**"),
                        Map.of("wiki", List.of("drafts/**"))),
                "sha256:abc123",
                new SnapshotManifest.Counts(1234, 17, 1217, 2, 0),
                new SnapshotManifest.Bytes(52100000L, 210000L));
    }

    private static ManifestEntry sampleEntry(String project, String path, ManifestEntry.Stored stored) {
        return new ManifestEntry(
                project,
                path,
                "projects/" + project + "/" + path,
                "doc-" + path.hashCode(),
                "lin-" + path.hashCode(),
                "markdown",
                "Title",
                "text/markdown",
                2048L,
                1700000000000L,
                1795212648001L,
                "storageid-" + path.hashCode(),
                false,
                "abc123def456",
                stored);
    }
}
