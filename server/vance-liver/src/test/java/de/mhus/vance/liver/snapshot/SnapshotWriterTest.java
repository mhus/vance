package de.mhus.vance.liver.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SnapshotWriter} — the on-disk side of a backup
 * run. Tests focus on the lifecycle (begin → ingest → finish) and
 * the hardlink-then-copy-then-download chain
 * ({@code planning/user-backup.md} §4.4 + §4.7).
 */
class SnapshotWriterTest {

    @TempDir
    Path tmp;

    private SnapshotWriter writer;
    private Path targetRoot;

    @BeforeEach
    void setup() throws IOException {
        targetRoot = tmp.resolve("snapshots");
        Files.createDirectories(targetRoot);
        writer = new SnapshotWriter(targetRoot);
    }

    @Test
    void begin_createsInProgressDirAndManifest() throws IOException {
        SnapshotManifest initial = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin("2026-10-21T09-30-00Z", initial, null);

        assertThat(dir).exists();
        assertThat(dir.getFileName().toString()).startsWith(".in-progress-");
        assertThat(Files.exists(dir.resolve("manifest.json"))).isTrue();
    }

    @Test
    void begin_refusesToOverwriteExistingInProgress() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin("2026-10-21T09-30-00Z", m, null);

        // Second begin with the same id is rejected — protects
        // against two parallel backups on the same target.
        assertThatThrownBy(() -> writer.begin("2026-10-21T09-30-00Z", m, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("in-progress snapshot already exists");
    }

    @Test
    void ingest_firstEntry_isDownloaded() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(m.snapshotId(), m, null);

        BackupEntryDto entry = sampleEntry("wiki", "notes/idee.md", "v1", "v1");
        ManifestEntry result = writer.ingest(entry, new ByteArrayInputStream("hello".getBytes()), null);

        assertThat(result.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
        assertThat(writer.targetRoot()
                        .resolve(".in-progress-2026-10-21T09-30-00Z")
                        .resolve("projects/wiki/notes/idee.md"))
                .hasContent("hello");
    }

    @Test
    void finish_atomicallyPublishesAndUpdatesLatest() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(m.snapshotId(), m, null);

        var counts = new SnapshotManifest.Counts(5, 3, 2, 0, 0);
        var bytes = new SnapshotManifest.Bytes(1000L, 600L);
        writer.finish(counts, bytes, "2026-10-21T09:31:12Z");

        // .in-progress-* gone, real snapshot dir present.
        assertThat(targetRoot.resolve(".in-progress-2026-10-21T09-30-00Z")).doesNotExist();
        Path published = targetRoot.resolve("2026-10-21T09-30-00Z");
        assertThat(published).exists();

        // Manifest now carries the final counts.
        SnapshotManifest back = new ManifestIO().readManifest(published);
        assertThat(back.counts()).isEqualTo(counts);
        assertThat(back.bytes()).isEqualTo(bytes);
        assertThat(back.finishedAt()).isEqualTo("2026-10-21T09:31:12Z");

        // LATEST marker points at the published snapshot.
        assertThat(targetRoot.resolve("LATEST")).hasContent("2026-10-21T09-30-00Z");
        assertThat(SnapshotWriter.latestSnapshot(targetRoot)).isEqualTo(published);
    }

    @Test
    void finish_secondRun_hardlinksFromPrevious() throws IOException {
        // First run: download two files into snapshot A.
        SnapshotManifest a = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(a.snapshotId(), a, null);
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), new ByteArrayInputStream("aaa".getBytes()), null);
        writer.ingest(sampleEntry("wiki", "b.md", "v2", "v2"), new ByteArrayInputStream("bbb".getBytes()), null);
        writer.finish(
                new SnapshotManifest.Counts(2, 2, 0, 0, 0), new SnapshotManifest.Bytes(6L, 6L), "2026-10-21T09:30:05Z");

        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");

        // Second run: same contentVersion → hardlink.
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        Path newSnapshot = targetRoot.resolve(".in-progress-2026-10-21T10-30-00Z");
        ManifestEntry entryA = writer2.ingest(
                sampleEntry("wiki", "a.md", "v1", "v1"), new ByteArrayInputStream("aaa".getBytes()), null);
        ManifestEntry entryB = writer2.ingest(
                sampleEntry("wiki", "b.md", "v2", "v2"), new ByteArrayInputStream("bbb".getBytes()), null);

        assertThat(entryA.stored()).isEqualTo(ManifestEntry.Stored.LINKED);
        assertThat(entryB.stored()).isEqualTo(ManifestEntry.Stored.LINKED);

        // Verify hardlink: same inode.
        Path linkedA = newSnapshot.resolve("projects/wiki/a.md");
        assertThat(Files.exists(linkedA)).isTrue();
        assertThat(Files.isSameFile(linkedA, publishedA.resolve("projects/wiki/a.md")))
                .isTrue();
    }

    @Test
    void ingest_changedContentVersion_downloadsAgain() throws IOException {
        // First run with v1.
        SnapshotManifest a = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(a.snapshotId(), a, null);
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), new ByteArrayInputStream("aaa".getBytes()), null);
        writer.finish(
                new SnapshotManifest.Counts(1, 1, 0, 0, 0), new SnapshotManifest.Bytes(3L, 3L), "2026-10-21T09:30:05Z");

        // Second run with v2 → no hardlink target, must download.
        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        ManifestEntry result = writer2.ingest(
                sampleEntry("wiki", "a.md", "v2", "v2"), new ByteArrayInputStream("aaa-new".getBytes()), null);
        assertThat(result.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
        Path newFile = targetRoot.resolve(".in-progress-2026-10-21T10-30-00Z").resolve("projects/wiki/a.md");
        assertThat(newFile).hasContent("aaa-new");
    }

    @Test
    void ingest_unknownContentVersion_downloadsNotCopies() throws IOException {
        // Previous snapshot has v1 for this path. New entry has
        // contentVersion=null (server didn't send one) → no match
        // in the previous index, must download.
        SnapshotManifest a = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(a.snapshotId(), a, null);
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), new ByteArrayInputStream("aaa".getBytes()), null);
        writer.finish(
                new SnapshotManifest.Counts(1, 1, 0, 0, 0), new SnapshotManifest.Bytes(3L, 3L), "2026-10-21T09:30:05Z");

        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        BackupEntryDto noVersion = sampleEntry("wiki", "a.md", "v2", null);
        ManifestEntry result = writer2.ingest(noVersion, new ByteArrayInputStream("aaa-new".getBytes()), null);
        assertThat(result.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
    }

    @Test
    void abort_removesInProgressDir() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), new ByteArrayInputStream("aaa".getBytes()), null);

        writer.abort();

        assertThat(dir).doesNotExist();
    }

    @Test
    void sha256_isHexEncoded() throws Exception {
        String hex = SnapshotWriter.sha256(new ByteArrayInputStream("hello".getBytes()));
        // SHA-256("hello") is a well-known value.
        assertThat(hex).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void latestSnapshot_returnsNullWhenNoMarker() {
        assertThat(SnapshotWriter.latestSnapshot(targetRoot)).isNull();
    }

    @Test
    void latestSnapshot_readsMarker() throws IOException {
        Files.writeString(targetRoot.resolve("LATEST"), "2026-10-21T09-30-00Z");
        Files.createDirectory(targetRoot.resolve("2026-10-21T09-30-00Z"));

        assertThat(SnapshotWriter.latestSnapshot(targetRoot)).isEqualTo(targetRoot.resolve("2026-10-21T09-30-00Z"));
    }

    // -- helpers --------------------------------------------------------------

    private static SnapshotManifest sampleManifest(String id) {
        return new SnapshotManifest(
                SnapshotManifest.CURRENT_FORMAT,
                "vancetope-backup 0.3.0-test",
                "http://localhost:9991",
                "acme",
                "tester",
                id,
                id,
                null,
                new SnapshotManifest.Filter(
                        new SnapshotManifest.ProjectsFilter(List.of("*"), List.of(), false),
                        List.of("_vance/**"),
                        java.util.Map.of()),
                null,
                new SnapshotManifest.Counts(0, 0, 0, 0, 0),
                new SnapshotManifest.Bytes(0L, 0L));
    }

    private static BackupEntryDto sampleEntry(String project, String path, String id, String contentVersion) {
        BackupEntryDto dto = new BackupEntryDto();
        dto.setId(id);
        dto.setProjectId(project);
        dto.setPath(path);
        dto.setTitle("Title " + path);
        dto.setSize(2048L);
        dto.setContentVersion(contentVersion);
        dto.setEncrypted(false);
        return dto;
    }
}
