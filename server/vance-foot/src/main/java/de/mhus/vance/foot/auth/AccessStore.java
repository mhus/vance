package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads, writes and deletes {@code .vancetope/access.yaml} ({@link AccessData}).
 * Pure file I/O over an explicit directory — the caller resolves the
 * directory via {@link VancePaths}.
 *
 * <p>Written {@code chmod 600} (owner read/write only) on POSIX file
 * systems; the permission tightening is skipped silently where the file
 * system does not support POSIX attributes (e.g. Windows). Every write goes
 * to an owner-only temp file in the same directory first and is then moved
 * over {@code access.yaml} atomically, so a reader never sees a half-written
 * file.
 *
 * <p>{@link #withLock} serialises read-modify-write sequences (the token
 * refresh) across threads <em>and</em> processes: several foot instances in
 * the same directory share one rotating refresh token, and two of them
 * refreshing at once would burn it (the server treats the second use of a
 * rotated refresh token as replay and revokes the login).
 */
@Component
@Slf4j
public class AccessStore {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Lock file next to {@code access.yaml}; holds no data, only the OS file lock. */
    public static final String LOCK_FILE = VancePaths.ACCESS_FILE + ".lock";

    /**
     * In-JVM guard per lock file — {@link FileChannel#lock()} is per process
     * and throws {@link java.nio.channels.OverlappingFileLockException} when a
     * second thread of the same JVM asks for it.
     */
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    /** A read-modify-write sequence run under {@link #withLock}. */
    @FunctionalInterface
    public interface LockedAction<T extends @Nullable Object> {
        T run() throws Exception;
    }

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The {@code access.yaml} path inside {@code dir}. */
    public Path file(Path dir) {
        return dir.resolve(VancePaths.ACCESS_FILE);
    }

    /** Whether {@code dir/access.yaml} exists. */
    public boolean exists(Path dir) {
        return Files.isRegularFile(file(dir));
    }

    /**
     * Loads credentials from {@code dir/access.yaml}, or empty when the
     * file is absent. A present-but-broken file raises {@link AccessStoreException}.
     */
    public Optional<AccessData> load(Path dir) {
        Path file = file(dir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            AccessData data = mapper.readValue(file.toFile(), AccessData.class);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            throw new AccessStoreException("Failed to read access file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes credentials to {@code dir/access.yaml}, creating {@code dir}
     * if needed. The content goes to an owner-only temp file first, which is
     * then moved over the target atomically — the token is never briefly
     * world-readable and never visible half-written.
     */
    public void save(Path dir, AccessData data) {
        Path file = file(dir);
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = createTempOwnerOnly(dir);
            mapper.writeValue(tmp.toFile(), data);
            restrictOwnerOnly(tmp);
            moveAtomically(tmp, file);
            tmp = null;
            restrictOwnerOnly(file);
            log.debug("wrote access file {}", file);
        } catch (IOException | RuntimeException e) {
            throw new AccessStoreException("Failed to write access file " + file + ": " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.debug("could not remove temp file {}: {}", tmp, e.getMessage());
                }
            }
        }
    }

    /**
     * Runs {@code action} while holding the access lock of {@code dir} —
     * exclusive across threads of this JVM and across processes (an OS file
     * lock on {@value #LOCK_FILE}). Re-entrant for the same thread. The action
     * must re-read {@code access.yaml} itself: what was read before the lock
     * may already be stale.
     */
    public <T extends @Nullable Object> T withLock(Path dir, LockedAction<T> action) throws Exception {
        Path lockFile = dir.resolve(LOCK_FILE).toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, k -> new ReentrantLock());
        if (jvmLock.isHeldByCurrentThread()) {
            return action.run();
        }
        jvmLock.lock();
        try {
            Files.createDirectories(dir);
            try (FileChannel channel = openLockFile(lockFile);
                    FileLock ignored = channel.lock()) {
                return action.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /** Deletes {@code dir/access.yaml}. Returns {@code true} if a file was removed. */
    public boolean delete(Path dir) {
        try {
            return Files.deleteIfExists(file(dir));
        } catch (IOException e) {
            throw new AccessStoreException("Failed to delete access file " + file(dir) + ": " + e.getMessage(), e);
        }
    }

    private static Path createTempOwnerOnly(Path dir) throws IOException {
        String prefix = "." + VancePaths.ACCESS_FILE + ".";
        if (supportsPosix(dir)) {
            return Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        return Files.createTempFile(dir, prefix, ".tmp");
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("atomic move not supported for {} — falling back to replace", target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static FileChannel openLockFile(Path lockFile) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (supportsPosix(lockFile) && !Files.exists(lockFile)) {
            return FileChannel.open(lockFile, options, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        return FileChannel.open(lockFile, options);
    }

    private static void restrictOwnerOnly(Path file) {
        if (!supportsPosix(file)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("could not restrict {} to owner-only (ignored): {}", file, e.getMessage());
        }
    }

    private static boolean supportsPosix(Path file) {
        return file.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
