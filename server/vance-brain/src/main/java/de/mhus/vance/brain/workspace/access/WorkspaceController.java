package de.mhus.vance.brain.workspace.access;

import de.mhus.vance.api.projects.WorkspaceTreeNodeDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceFileSizeExceededException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Web-UI-facing workspace endpoints. Resolves the owner pod for the requested
 * project via {@link WorkspaceRoutingCache} and proxies the call to that pod's
 * {@link WorkspaceInternalController}. The Layer-2 hop happens even when the
 * owner pod is the local one, so dev and prod exercise the same code path —
 * unless {@code vance.workspace.access.bypass-proxy=true}, in which case
 * Layer 1 calls {@link WorkspaceService} directly. See
 * {@code specification/workspace-access.md} §2 / §8.
 */
@RestController
@RequestMapping("/brain/{tenant}/projects/{project}/workspace")
@Slf4j
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceRoutingCache routingCache;
    private final WorkspaceAccessProperties properties;
    private final LocationService locationService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RequestAuthority authority;
    private final String internalToken;

    public WorkspaceController(WorkspaceService workspaceService,
                               WorkspaceRoutingCache routingCache,
                               WorkspaceAccessProperties properties,
                               LocationService locationService,
                               ObjectMapper objectMapper,
                               RequestAuthority authority,
                               @Value("${vance.internal.token:}") String internalToken) {
        this.workspaceService = workspaceService;
        this.routingCache = routingCache;
        this.properties = properties;
        this.locationService = locationService;
        this.objectMapper = objectMapper;
        this.authority = authority;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    @GetMapping("/tree")
    public WorkspaceTreeNodeDto tree(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(value = "path", required = false) @Nullable String path,
            @RequestParam(value = "depth", required = false, defaultValue = "1") int depth,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        if (properties.isBypassProxy()) {
            return treeDirect(project, path, depth);
        }
        ProjectPodKey key = new ProjectPodKey(tenant, project);
        String body = proxyGet(key, buildInternalPath(tenant, project, "tree", path, depth));
        try {
            return objectMapper.readValue(body, WorkspaceTreeNodeDto.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse workspace tree response from owner pod", e);
        }
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        if (properties.isBypassProxy()) {
            return fileDirect(project, path);
        }
        ProjectPodKey key = new ProjectPodKey(tenant, project);
        return proxyFile(key, buildInternalPath(tenant, project, "file", path, -1));
    }

    // ------------------------------------------------------------------
    // Bypass path — direct WorkspaceService call (test/dev only)
    // ------------------------------------------------------------------

    private WorkspaceTreeNodeDto treeDirect(String project, @Nullable String path, int depth) {
        try {
            if (path == null || path.isBlank()) {
                return workspaceService.treeRoot(project, depth);
            }
            String[] split = WorkspaceInternalController.splitDirAndRelative(path);
            return workspaceService.tree(project, split[0], split[1], depth);
        } catch (WorkspaceException e) {
            throw mapException(e);
        }
    }

    private ResponseEntity<byte[]> fileDirect(String project, String path) {
        String[] split = WorkspaceInternalController.splitDirAndRelative(path);
        if (split[1] == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "path must point at a file inside a RootDir");
        }
        byte[] bytes;
        try {
            bytes = workspaceService.readBytes(project, split[0], split[1], properties.getMaxFileSize());
        } catch (WorkspaceException e) {
            throw mapException(e);
        }
        return ResponseEntity.ok()
                .contentType(guessContentType(split[1]))
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(bytes.length))
                .body(bytes);
    }

    // ------------------------------------------------------------------
    // Proxy path — HTTP hop to the owner pod's Layer 2
    // ------------------------------------------------------------------

    private String proxyGet(ProjectPodKey key, String pathAndQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String podIp = resolveOrThrow(key, attempt);
            URI uri = URI.create("http://" + podIp + ":" + locationService.getServerPort() + pathAndQuery);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getReadTimeout())
                    .header(InternalAccessFilter.HEADER_INTERNAL_TOKEN, internalToken)
                    .GET()
                    .build();
            try {
                HttpResponse<String> resp = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                throw mapInternalStatus(resp.statusCode(), resp.body());
            } catch (ConnectException | HttpTimeoutException e) {
                routingCache.invalidate(key);
                if (attempt == 1) {
                    log.warn("Workspace proxy gave up after retry for {} {}: {}",
                            key.tenantId(), key.projectName(), e.toString());
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Workspace owner pod unreachable", e);
                }
                log.debug("Workspace proxy connect failed (attempt {}); retrying after cache refresh: {}",
                        attempt + 1, e.toString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Workspace proxy I/O error", e);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workspace proxy exhausted retries");
    }

    private ResponseEntity<byte[]> proxyFile(ProjectPodKey key, String pathAndQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String podIp = resolveOrThrow(key, attempt);
            URI uri = URI.create("http://" + podIp + ":" + locationService.getServerPort() + pathAndQuery);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getReadTimeout())
                    .header(InternalAccessFilter.HEADER_INTERNAL_TOKEN, internalToken)
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> resp = httpClient.send(request, BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String contentType = resp.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                            .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(resp.body().length))
                            .body(resp.body());
                }
                String body = new String(resp.body(), StandardCharsets.UTF_8);
                throw mapInternalStatus(resp.statusCode(), body);
            } catch (ConnectException | HttpTimeoutException e) {
                routingCache.invalidate(key);
                if (attempt == 1) {
                    log.warn("Workspace file proxy gave up after retry for {} {}: {}",
                            key.tenantId(), key.projectName(), e.toString());
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Workspace owner pod unreachable", e);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Workspace proxy I/O error", e);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workspace proxy exhausted retries");
    }

    private String resolveOrThrow(ProjectPodKey key, int attempt) {
        Optional<String> ip = attempt == 0 ? routingCache.lookup(key) : routingCache.refresh(key);
        return ip.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Project '" + key.tenantId() + "/" + key.projectName()
                        + "' is not claimed by any pod yet"));
    }

    private static String buildInternalPath(String tenant, String project, String op,
                                            @Nullable String path, int depth) {
        StringBuilder sb = new StringBuilder("/internal/workspace/")
                .append(URLEncoder.encode(tenant, StandardCharsets.UTF_8))
                .append('/')
                .append(URLEncoder.encode(project, StandardCharsets.UTF_8))
                .append('/')
                .append(op);
        boolean hasQuery = false;
        if (path != null && !path.isBlank()) {
            sb.append("?path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8));
            hasQuery = true;
        }
        if (depth >= 0) {
            sb.append(hasQuery ? '&' : '?').append("depth=").append(depth);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Error mapping
    // ------------------------------------------------------------------

    private static ResponseStatusException mapInternalStatus(int code, String body) {
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new ResponseStatusException(status, body == null ? status.getReasonPhrase() : body);
    }

    private static ResponseStatusException mapException(WorkspaceException e) {
        if (e instanceof WorkspaceFileSizeExceededException ex) {
            return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("Not found") || msg.startsWith("Unknown RootDir")
                || msg.startsWith("Not a regular file")) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, msg, e);
        }
        if (msg.contains("escapes RootDir") || msg.contains("NUL byte")) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg, e);
        }
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, msg, e);
    }

    private static MediaType guessContentType(String relativePath) {
        try {
            String probe = java.nio.file.Files.probeContentType(java.nio.file.Paths.get(relativePath));
            if (probe != null) {
                return MediaType.parseMediaType(probe);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // fall through
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
