package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.shared.kit.KitTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The kit's previously installed state, materialised on demand so a
 * three-way merge has a common ancestor.
 *
 * <p>Resolved <b>lazily</b>: fetching it means a second clone plus a full
 * inherit resolution, and the overwhelming majority of updates never need
 * it — only an artefact that is both under the {@code merge} policy and
 * locally modified does.
 *
 * <p>Not always obtainable. A folder source ({@code file://}) has no
 * commit to check out, a pinned commit may have been garbage-collected,
 * the remote may be unreachable. All of those end in "no base", and the
 * caller falls back to {@code keep} rather than guessing.
 */
@Slf4j
final class KitBaseTree {

    private final KitResolver resolver;
    private final KitWorkspace workspace;
    private final KitAccess access;
    private final KitInheritDto source;
    private final @Nullable String previousCommit;

    private boolean attempted;
    private KitResolver.@Nullable ResolvedKit resolved;
    private final Map<String, String> contents = new HashMap<>();

    KitBaseTree(KitResolver resolver, KitWorkspace workspace, KitAccess access,
            KitInheritDto source,
            @Nullable KitOriginDto previousOrigin) {
        this.resolver = resolver;
        this.workspace = workspace;
        this.access = access;
        this.source = source;
        this.previousCommit = previousOrigin == null ? null : previousOrigin.getCommit();
    }

    /**
     * Content of one document as the kit last installed it, or null when
     * the previous state cannot be reconstructed.
     */
    @Nullable String documentContent(String path) {
        if (!ensureResolved()) return null;
        return contents.get(path);
    }

    private boolean ensureResolved() {
        if (attempted) return resolved != null;
        attempted = true;
        if (previousCommit == null || previousCommit.isBlank()
                || previousCommit.startsWith("folder:")) {
            log.debug("KitBaseTree: no reconstructible previous state (commit='{}')",
                    previousCommit);
            return false;
        }
        KitInheritDto pinned = KitInheritDto.builder()
                .url(source.getUrl())
                .path(source.getPath())
                .branch(source.getBranch())
                .commit(previousCommit)
                .build();
        try {
            resolved = resolver.resolve(access, pinned);
        } catch (RuntimeException e) {
            // A missing base is a degraded merge, never a failed update —
            // the kit itself resolved fine, only its history did not.
            log.info("KitBaseTree: cannot reconstruct commit '{}' of {} — merge falls back"
                    + " to keep: {}", previousCommit, source.getUrl(), e.toString());
            return false;
        }
        readDocuments(resolved.buildRoot());
        return true;
    }

    private void readDocuments(Path buildRoot) {
        Path docsRoot = buildRoot.resolve(KitInstaller.DOCUMENTS_DIR);
        if (!Files.isDirectory(docsRoot)) return;
        for (Path file : KitTree.walkNoSymlinks(docsRoot)) {
            if (!Files.isRegularFile(file)) continue;
            String rel = docsRoot.relativize(file).toString().replace('\\', '/');
            try {
                contents.put(rel, Files.readString(file));
            } catch (IOException e) {
                log.debug("KitBaseTree: unreadable base file {} — skipping", file);
            }
        }
    }

    /** Release the extra checkout. Safe to call when nothing was resolved. */
    void cleanup() {
        if (resolved != null) {
            resolved.cleanup(workspace);
            resolved = null;
        }
    }
}
