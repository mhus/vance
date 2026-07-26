package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads, writes and deletes {@code .vance/access.yaml} ({@link AccessData}).
 * Pure file I/O over an explicit directory — the caller resolves the
 * directory via {@link VancePaths}.
 *
 * <p>Written {@code chmod 600} (owner read/write only) on POSIX file
 * systems; the permission tightening is skipped silently where the file
 * system does not support POSIX attributes (e.g. Windows).
 */
@Component
@Slf4j
public class AccessStore {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

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
            throw new AccessStoreException(
                    "Failed to read access file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes credentials to {@code dir/access.yaml}, creating {@code dir}
     * if needed, then tightens the file to owner-only where supported.
     */
    public void save(Path dir, AccessData data) {
        Path file = file(dir);
        try {
            Files.createDirectories(dir);
            // Pre-create with restrictive perms so the token is never briefly
            // world-readable between create and chmod.
            if (!Files.exists(file)) {
                createOwnerOnly(file);
            }
            mapper.writeValue(file.toFile(), data);
            restrictOwnerOnly(file);
            log.debug("wrote access file {}", file);
        } catch (IOException | RuntimeException e) {
            throw new AccessStoreException(
                    "Failed to write access file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Deletes {@code dir/access.yaml}. Returns {@code true} if a file was removed. */
    public boolean delete(Path dir) {
        try {
            return Files.deleteIfExists(file(dir));
        } catch (IOException e) {
            throw new AccessStoreException(
                    "Failed to delete access file " + file(dir) + ": " + e.getMessage(), e);
        }
    }

    private static void createOwnerOnly(Path file) throws IOException {
        if (supportsPosix(file)) {
            Files.createFile(file,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(file);
        }
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
