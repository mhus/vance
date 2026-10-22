package de.mhus.vance.liver.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        ManifestEntry result = writer.ingest(entry, body("hello"));

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
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));
        writer.ingest(sampleEntry("wiki", "b.md", "v2", "v2"), body("bbb"));
        writer.finish(
                new SnapshotManifest.Counts(2, 2, 0, 0, 0), new SnapshotManifest.Bytes(6L, 6L), "2026-10-21T09:30:05Z");

        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");

        // Second run: same contentVersion → hardlink.
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        Path newSnapshot = targetRoot.resolve(".in-progress-2026-10-21T10-30-00Z");
        ManifestEntry entryA = writer2.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));
        ManifestEntry entryB = writer2.ingest(sampleEntry("wiki", "b.md", "v2", "v2"), body("bbb"));

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
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));
        writer.finish(
                new SnapshotManifest.Counts(1, 1, 0, 0, 0), new SnapshotManifest.Bytes(3L, 3L), "2026-10-21T09:30:05Z");

        // Second run with v2 → no hardlink target, must download.
        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        ManifestEntry result = writer2.ingest(sampleEntry("wiki", "a.md", "v2", "v2"), body("aaa-new"));
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
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));
        writer.finish(
                new SnapshotManifest.Counts(1, 1, 0, 0, 0), new SnapshotManifest.Bytes(3L, 3L), "2026-10-21T09:30:05Z");

        Path publishedA = targetRoot.resolve("2026-10-21T09-30-00Z");
        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, publishedA);

        BackupEntryDto noVersion = sampleEntry("wiki", "a.md", "v2", null);
        ManifestEntry result = writer2.ingest(noVersion, body("aaa-new"));
        assertThat(result.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
    }

    @Test
    void abort_removesInProgressDir() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);
        writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));

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

    @Test
    void ingest_unchangedContent_neverOpensSource() throws IOException {
        SnapshotManifest a = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(a.snapshotId(), a, null);
        ManifestEntry first = writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("aaa"));
        writer.finish(
                new SnapshotManifest.Counts(1, 1, 0, 0, 0), new SnapshotManifest.Bytes(3L, 3L), "2026-10-21T09:30:05Z");

        SnapshotWriter writer2 = new SnapshotWriter(targetRoot);
        SnapshotManifest b = sampleManifest("2026-10-21T10-30-00Z");
        writer2.begin(b.snapshotId(), b, targetRoot.resolve("2026-10-21T09-30-00Z"));

        ManifestEntry second = writer2.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), () -> {
            throw new AssertionError("an unchanged document must not be downloaded");
        });

        assertThat(second.stored()).isEqualTo(ManifestEntry.Stored.LINKED);
        // The hash is carried over from the previous manifest.
        assertThat(second.sha256()).isEqualTo(first.sha256()).isNotNull();
        assertThat(writer2.bytesDownloaded()).isZero();
    }

    @Test
    void ingest_download_isHashedWhileStreaming() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        writer.begin(m.snapshotId(), m, null);

        ManifestEntry result = writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), body("hello"));

        assertThat(result.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(writer.bytesDownloaded()).isEqualTo(5L);
    }

    @Test
    void ingest_caseOnlyCollision_getsVariantInsteadOfOverwriting() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);

        ManifestEntry first = writer.ingest(sampleEntry("wiki", "Readme.md", "d1", "v1"), body("first"));
        ManifestEntry second = writer.ingest(sampleEntry("wiki", "README.md", "d2", "v2"), body("second"));

        assertThat(first.file()).isEqualTo("projects/wiki/Readme.md");
        assertThat(second.file()).isEqualTo("projects/wiki/README~2.md");
        assertThat(dir.resolve(first.file())).hasContent("first");
        assertThat(dir.resolve(second.file())).hasContent("second");
    }

    @Test
    void ingest_documentNamedLikeExistingFolder_getsVariant() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);

        writer.ingest(sampleEntry("wiki", "a/b.md", "d1", "v1"), body("inside"));
        ManifestEntry doc = writer.ingest(sampleEntry("wiki", "a", "d2", "v2"), body("document a"));

        assertThat(doc.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
        assertThat(doc.file()).isEqualTo("projects/wiki/a~2");
        assertThat(dir.resolve(doc.file())).hasContent("document a");
        assertThat(dir.resolve("projects/wiki/a/b.md")).hasContent("inside");
    }

    @Test
    void ingest_folderNamedLikeExistingDocument_getsFolderSuffix() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);

        writer.ingest(sampleEntry("wiki", "a", "d1", "v1"), body("document a"));
        ManifestEntry nested = writer.ingest(sampleEntry("wiki", "a/b.md", "d2", "v2"), body("inside"));

        assertThat(nested.stored()).isEqualTo(ManifestEntry.Stored.DOWNLOADED);
        assertThat(nested.file()).isEqualTo("projects/wiki/a~dir/b.md");
        assertThat(dir.resolve("projects/wiki/a")).hasContent("document a");
        assertThat(dir.resolve(nested.file())).hasContent("inside");
    }

    @Test
    void ingest_brokenDownload_recordsFailedAndLeavesNoFile() throws IOException {
        SnapshotManifest m = sampleManifest("2026-10-21T09-30-00Z");
        Path dir = writer.begin(m.snapshotId(), m, null);

        ManifestEntry result = writer.ingest(sampleEntry("wiki", "a.md", "v1", "v1"), () -> {
            throw new IOException("connection reset");
        });

        assertThat(result.stored()).isEqualTo(ManifestEntry.Stored.FAILED);
        assertThat(result.sha256()).isNull();
        assertThat(dir.resolve("projects/wiki/a.md")).doesNotExist();
    }

    // -- helpers --------------------------------------------------------------

    private static SnapshotWriter.ContentSource body(String text) {
        return () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

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
