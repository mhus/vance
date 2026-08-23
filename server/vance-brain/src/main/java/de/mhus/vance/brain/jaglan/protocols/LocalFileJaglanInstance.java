package de.mhus.vance.brain.jaglan.protocols;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceRootService;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import lombok.extern.slf4j.Slf4j;

/**
 * A directory on the machine the brain runs on, exposed under
 * {@code _ext/<mount>/}.
 *
 * <p>Exists so the whole path — namespace, shell rows, TTL, redirect — is
 * exercisable without a foreign system, and because "point Vance at a folder
 * of PDFs" is a real use of its own.
 *
 * <p><b>Read-only unless asked otherwise.</b> {@code writable} defaults to
 * false: this protocol can write to the host filesystem, and a mount that
 * gains that power by omission would be the wrong default in the one place
 * where the blast radius is the machine rather than a document.
 *
 * <p>Every path goes through {@link WorkspaceRootService}, which is
 * symlink-aware — the same confinement {@code work_file_*} uses. An escape is
 * reported as a <b>refusal</b> rather than an outage: retrying will not make
 * {@code ../../etc/passwd} legal.
 */
@Slf4j
public class LocalFileJaglanInstance implements JaglanInstance {

    private final String mount;
    private final Path root;
    private final boolean writable;
    private final Duration metadataTtl;
    private final String displayName;
    private final WorkspaceRootService confinement;

    LocalFileJaglanInstance(
            String mount, Path root, boolean writable, Duration metadataTtl,
            String displayName, WorkspaceRootService confinement) {
        this.mount = mount;
        this.root = root;
        this.writable = writable;
        this.metadataTtl = metadataTtl;
        this.displayName = displayName;
        this.confinement = confinement;
    }

    @Override
    public String mount() {
        return mount;
    }

    @Override
    public String protocolId() {
        return LocalFileJaglanProtocol.ID;
    }

    @Override
    public JaglanCapabilities capabilities() {
        // itemCount is left unknown rather than walked: counting a deep tree
        // on every capabilities fetch would turn a cheap declaration into a
        // filesystem crawl, and the folder listing already knows its own size.
        return new JaglanCapabilities(
                writable ? MountAccess.RW : MountAccess.RO,
                /* canSearch */ false,
                /* itemCount */ null,
                metadataTtl,
                /* maxBytes */ null,
                displayName);
    }

    @Override
    public Optional<MountedStat> stat(String pathInMount) {
        Path target = resolve(pathInMount);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(toStat(target, pathInMount));
    }

    @Override
    public List<MountedStat> list(String pathInMount) {
        Path folder = resolve(pathInMount);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        String prefix = pathInMount == null || pathInMount.isBlank() ? "" : pathInMount + "/";
        List<MountedStat> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(folder)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!confinement.isWithin(root, child)) {
                    // A symlink pointing out of the mount. Skipped rather than
                    // thrown: one bad entry must not take the folder down, and
                    // listing it would leak size, mtime and mime type of a file
                    // outside the mount — plus promise a read that open() then
                    // correctly refuses.
                    log.debug("Jaglan local mount '{}': '{}' leaves the root, skipped",
                            mount, child);
                    continue;
                }
                out.add(toStat(child, prefix + name));
            }
        } catch (IOException e) {
            throw JaglanProtocolException.unavailable(mount,
                    "cannot list '" + pathInMount + "' in mount '" + mount + "': " + e, e);
        }
        return out;
    }

    @Override
    public InputStream open(String pathInMount) {
        Path target = resolve(pathInMount);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw JaglanProtocolException.unavailable(mount,
                    "cannot read '" + pathInMount + "' in mount '" + mount + "': " + e, e);
        }
    }

    @Override
    public MountedStat write(String pathInMount, InputStream content) {
        requireWritable("write");
        Path target = resolve(pathInMount);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw JaglanProtocolException.unavailable(mount,
                    "cannot write '" + pathInMount + "' in mount '" + mount + "': " + e, e);
        }
        return toStat(target, pathInMount);
    }

    @Override
    public void delete(String pathInMount) {
        requireWritable("delete");
        Path target = resolve(pathInMount);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw JaglanProtocolException.unavailable(mount,
                    "cannot delete '" + pathInMount + "' in mount '" + mount + "': " + e, e);
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private void requireWritable(String op) {
        if (!writable) {
            throw new JaglanProtocolException(mount,
                    "mount '" + mount + "' is read-only — set "
                            + "writable: true in _vance/config/mounts/" + mount + ".yaml to allow " + op);
        }
    }

    /**
     * Confined resolution. An empty path is the mount root, which
     * {@link WorkspaceRootService#resolveWithin} rejects as blank — so that
     * case is handled here rather than by relaxing the confinement check.
     */
    private Path resolve(String pathInMount) {
        if (pathInMount == null || pathInMount.isBlank()) {
            return root;
        }
        try {
            return confinement.resolveWithin(root, pathInMount);
        } catch (WorkspaceException e) {
            // A refusal, not an outage: retrying will not make an escape legal.
            throw new JaglanProtocolException(mount,
                    "path '" + pathInMount + "' escapes mount '" + mount + "'");
        }
    }

    private MountedStat toStat(Path target, String pathInMount) {
        boolean directory = Files.isDirectory(target);
        long size = 0;
        Long modifiedAtMs = null;
        String etag = null;
        try {
            if (!directory) {
                size = Files.size(target);
            }
            long modified = Files.getLastModifiedTime(target).toMillis();
            modifiedAtMs = modified;
            // Cheap change token from what the filesystem already told us —
            // the document layer has no storageId to build one from.
            etag = Long.toHexString(modified) + '-' + Long.toHexString(size);
        } catch (IOException e) {
            log.debug("Jaglan local: cannot read attributes of '{}': {}", target, e.toString());
        }
        String mimeType = null;
        if (!directory) {
            try {
                mimeType = Files.probeContentType(target);
            } catch (IOException e) {
                // Leaving it null is fine — the document layer falls back to
                // the file extension.
                log.debug("Jaglan local: cannot probe mime of '{}': {}", target, e.toString());
            }
        }
        return new MountedStat(pathInMount, directory, size, mimeType, etag, modifiedAtMs,
                writable ? MountAccess.RW : MountAccess.RO);
    }
}
