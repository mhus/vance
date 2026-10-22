package de.mhus.vance.liver.snapshot;

import de.mhus.vance.api.backup.BackupEntryDto;
import de.mhus.vance.liver.fs.PathMapper;
import de.mhus.vance.liver.manifest.ManifestEntry;
import de.mhus.vance.liver.manifest.ManifestIO;
import de.mhus.vance.liver.manifest.SnapshotManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   <li>{@link #ingest(BackupEntryDto, ContentSource)} is called once
 *       per document. The writer tries a hardlink from the previous
 *       snapshot; falls back to a local copy when the hardlink is not
 *       possible (different filesystem, link budget exceeded); opens the
 *       content source only when the entry is new or has changed.
 *       Per-file failures are recorded with {@link ManifestEntry.Stored#FAILED}
 *       — a single bad document does not abort the run.
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
 *   <li>Copy fails (previous file missing/unreadable) → download.
 *   <li>Download fails → record FAILED, no file.
 * </ol>
 */
@NullMarked
public final class SnapshotWriter {

    /** Consecutive link failures, without any success, after which a run stops trying links. */
    public static final int LINK_FAILURE_LIMIT = 50;

    /** Name of the file inside each snapshot that names the latest snapshot. */
    public static final String LATEST_FILENAME = "LATEST";

    private final Path targetRoot;
    private final ManifestIO io;
    private final DateTimeFormatter snapshotIdFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private @Nullable Path inProgressDir;
    private @Nullable Path previousSnapshotDir;
    private @Nullable Map<String, ManifestEntry> previousByContentVersion;
    private final Set<String> allocatedFiles = new HashSet<>();
    private final Set<String> allocatedDirs = new HashSet<>();
    private long bytesDownloaded;
    private long linkSuccesses;
    private int consecutiveLinkFailures;

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
     * Supplies the body of a document that has to be downloaded. Only
     * opened when the previous snapshot cannot provide the bytes — an
     * unchanged document never touches the network.
     */
    @FunctionalInterface
    public interface ContentSource {
        InputStream open() throws IOException;
    }

    /**
     * Writes one entry and appends it to {@code entries.jsonl}.
     *
     * <p>Chain, decided per file (planning §4.4): a document whose
     * {@code contentVersion} is in the previous snapshot is hardlinked,
     * or copied locally when the link fails; only otherwise is
     * {@code source} opened and the body streamed to disk, hashed on
     * the fly. A failed download is recorded as {@link ManifestEntry.Stored#FAILED}
     * with no file left behind.
     *
     * <p>Exceptions thrown by {@code source} other than {@link IOException}
     * propagate — the caller decides whether they end the run (an auth
     * failure) or just this entry.
     */
    public ManifestEntry ingest(BackupEntryDto entry, ContentSource source) throws IOException {
        Path dir = requireInProgress();
        String file = allocateFile(entry.getProjectId(), entry.getPath());
        Path target = dir.resolve(file);
        Files.createDirectories(target.getParent());

        ManifestEntry previous = previousEntryFor(entry.getContentVersion());
        if (previous != null) {
            Path previousFile = requirePreviousDir().resolve(previous.file());
            if (linkAttemptsWorthwhile()) {
                try {
                    Files.createLink(target, previousFile);
                    linkSuccesses++;
                    consecutiveLinkFailures = 0;
                    return append(dir, entryResult(entry, file, ManifestEntry.Stored.LINKED, previous.sha256()));
                } catch (UnsupportedOperationException | IOException e) {
                    // Per file, not per run: network shares fail some links
                    // and not others, and every filesystem caps the links
                    // per inode (NTFS 1023). Fall through to a local copy.
                    consecutiveLinkFailures++;
                }
            }
            try {
                Files.copy(previousFile, target, LinkOption.NOFOLLOW_LINKS);
                return append(dir, entryResult(entry, file, ManifestEntry.Stored.COPIED, previous.sha256()));
            } catch (IOException e) {
                // Previous file missing or unreadable — download instead.
                Files.deleteIfExists(target);
            }
        }

        try (InputStream body = source.open();
                DigestInputStream digesting = new DigestInputStream(body, newSha256())) {
            // No REPLACE_EXISTING: allocateFile hands out a free name, so an
            // existing file here is a bug — failing beats silently
            // overwriting another document's bytes.
            long written = Files.copy(digesting, target);
            bytesDownloaded += written;
            String sha256 =
                    HexFormat.of().formatHex(digesting.getMessageDigest().digest());
            return append(dir, entryResult(entry, file, ManifestEntry.Stored.DOWNLOADED, sha256));
        } catch (IOException e) {
            Files.deleteIfExists(target);
            return recordFailed(entry, file);
        }
    }

    /**
     * Records an entry that could not be fetched at all (for example a
     * document deleted between listing and download). Allocates its file
     * name like any other entry so names stay stable within the snapshot.
     */
    public ManifestEntry recordFailed(BackupEntryDto entry) throws IOException {
        requireInProgress();
        return recordFailed(entry, allocateFile(entry.getProjectId(), entry.getPath()));
    }

    /** Bytes written from downloads in this run (links and copies cost no transfer). */
    public long bytesDownloaded() {
        return bytesDownloaded;
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

    private Path requirePreviousDir() {
        if (previousSnapshotDir == null) {
            throw new IllegalStateException("no previous snapshot");
        }
        return previousSnapshotDir;
    }

    private @Nullable ManifestEntry previousEntryFor(@Nullable String contentVersion) {
        if (contentVersion == null || previousByContentVersion == null) {
            return null;
        }
        return previousByContentVersion.get(contentVersion);
    }

    /**
     * Skip link attempts once the target has refused {@value #LINK_FAILURE_LIMIT}
     * in a row without a single success (exFAT/FAT cannot link at all).
     * Only saves failed syscalls — {@code stored} is still per file, and the
     * next run tries again.
     */
    private boolean linkAttemptsWorthwhile() {
        return linkSuccesses > 0 || consecutiveLinkFailures < LINK_FAILURE_LIMIT;
    }

    /**
     * Hands out the on-disk path for a document, unique within this
     * snapshot (planning §4.5). Names are compared case-folded because the
     * default APFS and NTFS volumes are case-insensitive: {@code Readme.md}
     * and {@code README.md} would otherwise land on one file. A document
     * name that is already a folder gets a {@code ~N} variant; a folder
     * segment that is already a document gets {@link PathMapper#FOLDER_CONFLICT_SUFFIX}.
     * Allocation follows listing order, which is stable ({@code _id}).
     */
    private String allocateFile(String project, String documentPath) {
        String[] segments = ("projects/" + PathMapper.map(project) + "/" + PathMapper.map(documentPath)).split("/");
        StringBuilder dirPath = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            String candidate = join(dirPath, segment);
            while (allocatedFiles.contains(fold(candidate))) {
                segment = segment + PathMapper.FOLDER_CONFLICT_SUFFIX;
                candidate = join(dirPath, segment);
            }
            allocatedDirs.add(fold(candidate));
            dirPath.setLength(0);
            dirPath.append(candidate);
        }
        String base = join(dirPath, segments[segments.length - 1]);
        String file = base;
        for (int attempt = 2; allocatedFiles.contains(fold(file)) || allocatedDirs.contains(fold(file)); attempt++) {
            file = PathMapper.collisionVariant(base, attempt);
        }
        allocatedFiles.add(fold(file));
        return file;
    }

    private static String join(CharSequence dir, String segment) {
        return dir.isEmpty() ? segment : dir + "/" + segment;
    }

    private static String fold(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private ManifestEntry append(Path dir, ManifestEntry result) throws IOException {
        io.appendEntry(dir, result);
        return result;
    }

    private ManifestEntry recordFailed(BackupEntryDto entry, String file) throws IOException {
        return append(requireInProgress(), entryResult(entry, file, ManifestEntry.Stored.FAILED, null));
    }

    /**
     * Reads the previous snapshot's {@code entries.jsonl} and builds a
     * {@code contentVersion → entry} index — the entry carries the file
     * to link and the hash to carry over. Failed entries have no file.
     */
    private static Map<String, ManifestEntry> loadPreviousIndex(Path previousSnapshot) throws IOException {
        Map<String, ManifestEntry> index = new HashMap<>();
        for (ManifestEntry entry : new ManifestIO().readEntries(previousSnapshot)) {
            if (entry.contentVersion() != null && entry.stored() != ManifestEntry.Stored.FAILED) {
                index.put(entry.contentVersion(), entry);
            }
        }
        return index;
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
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

    /** Computes the SHA-256 of a byte stream (consumed, not closed). */
    public static String sha256(InputStream body) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = body.read(buf)) > 0) {
            digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
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
}
