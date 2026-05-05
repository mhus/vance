package de.mhus.vance.brain.workspace.access;

import de.mhus.vance.api.projects.WorkspaceTreeNodeDto;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceFileSizeExceededException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pod-internal workspace endpoints. Reachable only with a valid
 * {@code X-Vance-Internal-Token} (see {@link InternalAccessFilter}). Layer 1
 * (the public {@code /brain/...} controller) calls these directly on the
 * owner pod; nothing else should.
 *
 * <p>The path scheme is {@code /internal/workspace/{tenant}/{project}/...} —
 * tenant/project come straight from the Layer-1 path and are forwarded to
 * {@link WorkspaceService} as-is. Tenant/project authorization happened on
 * Layer 1 against the user's JWT; this controller does not re-check it.
 */
@RestController
@RequestMapping("/internal/workspace/{tenant}/{project}")
@Slf4j
public class WorkspaceInternalController {

    private final WorkspaceService workspaceService;
    private final WorkspaceAccessProperties properties;

    public WorkspaceInternalController(WorkspaceService workspaceService,
                                       WorkspaceAccessProperties properties) {
        this.workspaceService = workspaceService;
        this.properties = properties;
    }

    @GetMapping("/tree")
    public WorkspaceTreeNodeDto tree(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(value = "path", required = false) @Nullable String path,
            @RequestParam(value = "depth", required = false, defaultValue = "1") int depth) {
        try {
            if (path == null || path.isBlank()) {
                return workspaceService.treeRoot(tenant, project, depth);
            }
            String[] split = splitDirAndRelative(path);
            return workspaceService.tree(tenant, project, split[0], split[1], depth);
        } catch (WorkspaceException e) {
            throw mapException(e);
        }
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam("path") String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String[] split = splitDirAndRelative(path);
        if (split[1] == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "path must point at a file inside a RootDir, not the RootDir itself");
        }
        byte[] bytes;
        try {
            bytes = workspaceService.readBytes(tenant, project, split[0], split[1], properties.getMaxFileSize());
        } catch (WorkspaceException e) {
            throw mapException(e);
        }
        MediaType contentType = guessContentType(split[1]);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(bytes.length))
                .body(bytes);
    }

    /**
     * Split {@code <dirName>[/<relative>]} into the RootDir name and the
     * remainder. {@code relativePath} comes back {@code null} when the input
     * names only the RootDir.
     */
    static String[] splitDirAndRelative(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            return new String[] { trimmed, null };
        }
        return new String[] { trimmed.substring(0, slash), trimmed.substring(slash + 1) };
    }

    private static MediaType guessContentType(String relativePath) {
        try {
            Path p = Paths.get(relativePath);
            String probe = Files.probeContentType(p);
            if (probe != null) {
                return MediaType.parseMediaType(probe);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // fall through to default
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static ResponseStatusException mapException(WorkspaceException e) {
        if (e instanceof WorkspaceFileSizeExceededException ex) {
            return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("Not found")
                || msg.startsWith("Unknown RootDir")
                || msg.startsWith("Not a regular file")) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, msg, e);
        }
        if (msg.contains("escapes RootDir") || msg.contains("NUL byte")) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg, e);
        }
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg, e);
    }
}
