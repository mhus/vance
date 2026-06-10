package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.research.SearchScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Writes payloads loaded by {@link de.mhus.vance.toolpack.research.SearchProviderInstance#loadContent}
 * into the project's workspace temp-root. Lifecycle of the stashed
 * files is owned by {@code WorkspaceService.suspendAll(...)}; this
 * store does not implement its own cleanup.
 *
 * <p>All paths returned by {@link #stash} are absolute and live under
 * {@code <project-temp-root>/zarniwoop/}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZarniwoopContentStore {

    /** Subdirectory in the project temp-root all stashed files share. */
    static final String STASH_SUBDIR = "zarniwoop";

    /**
     * Fallback creator-process id used when the request comes in
     * without a process scope (admin tools, integration tests). The
     * workspace temp-root keys per creator, so a stable fallback keeps
     * one shared directory for those callers instead of fragmenting.
     */
    static final String FALLBACK_PROCESS_ID = "zarniwoop";

    private final WorkspaceService workspaceService;

    /**
     * Write {@code bytes} into the project temp-root and return the
     * absolute path. {@code filename} is sanitised — only alnum, dot,
     * dash and underscore survive — and prefixed with the millis
     * timestamp so two stashes of the same filename don't collide.
     */
    public Path stash(SearchScope scope, String filename, byte[] bytes, String mimeType) {
        if (scope == null) {
            throw new ZarniwoopException("scope is required for content stash");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("stash requires a project scope");
        }
        if (bytes == null) {
            throw new ZarniwoopException("stash bytes must not be null");
        }

        String creatorProcess = StringUtils.isBlank(scope.processId())
                ? FALLBACK_PROCESS_ID
                : scope.processId();
        RootDirHandle tempRoot = workspaceService.getOrCreateTempRootDir(
                scope.tenantId(), scope.projectId(), creatorProcess);
        Path dir = tempRoot.getPath().resolve(STASH_SUBDIR);
        String safeName = sanitize(filename);
        Path target = dir.resolve(Instant.now().toEpochMilli() + "-" + safeName);
        try {
            Files.createDirectories(dir);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new ZarniwoopException(
                    "stash write failed for '" + target + "': " + e.getMessage(), e);
        }
        log.debug("Zarniwoop: stashed {} bytes ({}) at '{}'",
                bytes.length, mimeType, target);
        return target;
    }

    /** Strip everything except [A-Za-z0-9._-]; clamp to a sensible length. */
    static String sanitize(String filename) {
        if (StringUtils.isBlank(filename)) return "payload";
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
        }
        return cleaned;
    }
}
