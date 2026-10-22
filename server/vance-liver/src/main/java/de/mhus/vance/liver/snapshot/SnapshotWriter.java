package de.mhus.vance.liver.snapshot;

import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.liver.fs.PathMapper;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Writes one snapshot to disk — the on-disk side of a backup run
 * ({@code planning/user-backup.md} §4.4–§4.7).
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #begin(String, SnapshotManifest, String)} creates the
 *       {@code .in-progress-<snapshotId>} directory and a fresh
 *       {@code LATEST} marker. The directory stays hidden (dot-prefix)
 *       until the run finishes.
 *   <li>{@link #ingest(BackupEntryDto, BackupEntryDto, InputStream, String)}
 *       is called once per document. The writer tries a hardlink
 *       from the previous snapshot; falls back to copy when the
 *       hardlink is not possible (different filesystem, link budget
 *       exceeded); falls back to download from the byte stream when
 *       the entry is new or has changed. Per-file failures are
 *       recorded with {@link ManifestEntry.Stored#FAILED} — a single
 *       bad document does not abort the run.
 *   <li>{@link #finish(Counts, Bytes)} closes the manifest with the
 *       final counts, atomically renames the in-progress directory
 *       to the published snapshot id, and updates {@code LATEST}.
 * </ol>
 *
 * <h2>Atomicity</h2>
 *
 * <p>The {@code .in-progress-…} rename is the atomicity boundary.
 * Until {@link #finish} returns, the snapshot is invisible to
 * {@code list} / {@code verify}. A crash mid-run leaves a
 * {@code .in-progress-…} directory behind; the next run detects
 * that and refuses to start (or resumes, depending on a future
 * option).
 *
 * <p>{@link #finish} uses {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}
 * where the filesystem supports it, and falls back to a regular
 * {@code Files.move} otherwise.
 *
 * <h2>Hardlink fallback chain</h2>
 *
 * <ol>
 *   <li>New entry (no previous snapshot, or no matching
 *       {@code contentVersion}) → download.
 *   <li>Same entry in previous snapshot on the same filesystem →
 *       hardlink.
 *   <li>Hardlink fails (cross-device, link budget) → copy.
 *   <li>Copy fails (permission, disk full) → record FAILED, no file.
 * </ol>
 */
@NullMarked
public final class SnapshotWriter {

    /** Maximum number of copy-fallback failures before the writer gives up entirely. */
    public static final int COPY_FAILURE_LIMIT = 50;

    /** Name of the file inside each snapshot that names the latest snapshot. */
    public static final String LATEST_FILENAME = "LATEST";

    private final Path targetRoot;
    private final ManifestIO io;
    private final DateTimeFormatter snapshotIdFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private @Nullable Path inProgressDir;
    private @Nullable Path previousSnapshotDir;
    private @Nullable Map<String, Path> previousByContentVersion;

    public SnapshotWriter(Path targetRoot) {
        this(targetRoot, new ManifestIO());
    }

    /** Test-friendly constructor that injects the manifest IO. */
    SnapshotWriter(Path targetRoot, ManifestIO io) {
        this.targetRoot = targetRoot;
        this.io = io;
    }

    /** Returns the snapshot root — exposed for tests and logging. */
    public Path targetRoot() {
        return targetRoot;
    }

    /**
     * Starts a new snapshot. {@code previousSnapshot} is non-null when
     * a previous run produced a snapshot under the same filter — the
     * writer will try to hardlink from there. {@code user} is recorded
     * in the manifest for the operator's reference.
     *
     * @return the freshly-created {@code .in-progress-<snapshotId>} directory
     */
    public Path begin(String snapshotId, SnapshotManifest manifest, @Nullable Path previousSnapshot)
            throws IOException {
        Files.createDirectories(targetRoot);
        this.previousSnapshotDir = previousSnapshot;
        if (previousSnapshot != null) {
            // Build the index once per run: contentVersion → file.
            this.previousByContentVersion = loadPreviousIndex(previousSnapshot);
        } else {
            this.previousByContentVersion = null;
        }
        Path dir = targetRoot.resolve(".in-progress-" + snapshotId);
        // Refuse to start over an in-progress run. Two parallel
        // backups on the same target would race here; the caller
        // (RunCommand) acquires the .lock file.
        if (Files.exists(dir)) {
            throw new IOException("An in-progress snapshot already exists at " + dir
                    + " — refusing to overwrite. Remove it manually or wait for the other run.");
        }
        Files.createDirectories(dir);
        io.writeManifest(dir, manifest);
        this.inProgressDir = dir;
        return dir;
    }

    /**
     * Writes one entry. The byte stream is consumed and closed.
     * Returns a {@link ManifestEntry} for the manifest — the caller
     * appends it to {@code entries.jsonl}.
     */
    public ManifestEntry ingest(BackupEntryDto entry, InputStream body, String contentSha256) throws IOException {
        Path dir = requireInProgress();
        // Layout (planning §4.4): <snapshot>/projects/<project>/<path>.
        // The 'projects/' segment is fixed so the snapshot folder is
        // self-describing on disk — no extra metadata file needed to
        // tell where a project starts.
        String file = "projects/" + PathMapper.map(entry.getProjectId()) + "/" + PathMapper.map(entry.getPath());
        Path target = dir.resolve(file);

        Files.createDirectories(target.getParent());

        ManifestEntry result;
        // 1. Hardlink?
        Path previous = previousFileFor(entry.getContentVersion());
        if (previous != null) {
            try {
                Files.createLink(target, previous);
                result = entryResult(entry, file, ManifestEntry.Stored.LINKED, contentSha256);
                io.appendEntry(dir, result);
                return result;
            } catch (UnsupportedOperationException | IOException e) {
                // cross-device (EXDEV), link budget exceeded, or
                // permission denied — fall through to copy.
            }
        }
        // 2. Copy from previous snapshot if available (different FS).
        if (previous != null) {
            try {
                Files.copy(previous, target, LinkOption.NOFOLLOW_LINKS);
                result = entryResult(entry, file, ManifestEntry.Stored.COPIED, contentSha256);
                io.appendEntry(dir, result);
                return result;
            } catch (IOException e) {
                // fall through to download.
            }
        }
        // 3. Download.
        try {
            Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            result = entryResult(entry, file, ManifestEntry.Stored.DOWNLOADED, contentSha256);
            io.appendEntry(dir, result);
            return result;
        } catch (IOException e) {
            result = new ManifestEntry(
                    entry.getProjectId(),
                    entry.getPath(),
                    file,
                    entry.getId(),
                    entry.getLineageId(),
                    entry.getKind(),
                    entry.getTitle(),
                    entry.getMimeType(),
                    entry.getSize(),
                    entry.getCreatedAtMs(),
                    entry.getUpdatedAtMs(),
                    entry.getContentVersion(),
                    entry.isEncrypted(),
                    null,
                    ManifestEntry.Stored.FAILED);
            io.appendEntry(dir, result);
            return result;
        }
    }

    /** Closes the snapshot: writes final counts and atomically publishes. */
    public void finish(SnapshotManifest.Counts counts, SnapshotManifest.Bytes bytes, String finishedAtIso)
            throws IOException {
        Path dir = requireInProgress();
        SnapshotManifest current = io.readManifest(dir);
        SnapshotManifest finalManifest = new SnapshotManifest(
                current.format(),
                current.tool(),
                current.server(),
                current.tenant(),
                current.user(),
                current.snapshotId(),
                current.startedAt(),
                finishedAtIso,
                current.filter(),
                current.filterHash(),
                counts,
                bytes);
        io.writeManifest(dir, finalManifest);

        // Atomic publish: rename .in-progress-<id> → <id>
        Path published = targetRoot.resolve(current.snapshotId());
        try {
            Files.move(dir, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(dir, published);
        }
        // Update LATEST marker — a regular file with the snapshot id.
        Files.writeString(targetRoot.resolve(LATEST_FILENAME), current.snapshotId());
    }

    /**
     * Aborts the in-progress snapshot — best-effort cleanup. Used
     * when a runtime exception escapes {@code run} so a future
     * restart does not see a half-written snapshot.
     */
    public void abort() {
        if (inProgressDir != null && Files.exists(inProgressDir)) {
            try {
                // Walk and delete. Not atomic, but this is cleanup.
                try (var stream = Files.walk(inProgressDir)) {
                    stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    // -- internals ------------------------------------------------------------

    private Path requireInProgress() {
        if (inProgressDir == null) {
            throw new IllegalStateException("SnapshotWriter.begin() was not called");
        }
        return inProgressDir;
    }

    private @Nullable Path previousFileFor(@Nullable String contentVersion) {
        if (contentVersion == null || previousByContentVersion == null) {
            return null;
        }
        return previousByContentVersion.get(contentVersion);
    }

    /**
     * Reads the previous snapshot's {@code entries.jsonl} and builds a
     * {@code contentVersion → relative file path} index. Used to
     * decide whether to hardlink or download.
     */
    private static Map<String, Path> loadPreviousIndex(Path previousSnapshot) throws IOException {
        Map<String, Path> index = new HashMap<>();
        ManifestIO io = new ManifestIO();
        for (ManifestEntry entry : io.readEntries(previousSnapshot)) {
            if (entry.contentVersion() != null && entry.file() != null) {
                index.put(entry.contentVersion(), previousSnapshot.resolve(entry.file()));
            }
        }
        return index;
    }

    private static ManifestEntry entryResult(
            BackupEntryDto entry, String file, ManifestEntry.Stored stored, @Nullable String sha256) {
        return new ManifestEntry(
                entry.getProjectId(),
                entry.getPath(),
                file,
                entry.getId(),
                entry.getLineageId(),
                entry.getKind(),
                entry.getTitle(),
                entry.getMimeType(),
                entry.getSize(),
                entry.getCreatedAtMs(),
                entry.getUpdatedAtMs(),
                entry.getContentVersion(),
                entry.isEncrypted(),
                sha256,
                stored);
    }

    /** Computes the SHA-256 of a byte stream (consumed and closed). */
    public static String sha256(InputStream body) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = body.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /**
     * Generates the snapshot id from a timestamp — UTC ISO-style with
     * {@code :} replaced by {@code -} so the directory name is
     * portable across Windows and macOS.
     */
    public String newSnapshotId(Instant startedAt) {
        return snapshotIdFormatter.format(startedAt);
    }

    /** Returns the path the latest snapshot lives at, or null if none. */
    public static @Nullable Path latestSnapshot(Path targetRoot) {
        Path marker = targetRoot.resolve(LATEST_FILENAME);
        if (!Files.exists(marker)) {
            return null;
        }
        try {
            String id = Files.readString(marker).trim();
            if (id.isEmpty()) {
                return null;
            }
            return targetRoot.resolve(id);
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads an HttpResponse<InputStream> body fully — exposed for the run command. */
    public static byte[] readAll(HttpResponse<InputStream> resp) throws IOException {
        try (var body = resp.body()) {
            return body.readAllBytes();
        }
    }
}
