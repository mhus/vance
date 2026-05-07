package de.mhus.vance.brain.execution.access;

import de.mhus.vance.api.execution.ExecutionInsightsDto;
import de.mhus.vance.api.execution.ExecutionTailDto;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionRouter;
import de.mhus.vance.brain.execution.ExecutionScopeFilter;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import de.mhus.vance.brain.workspace.access.ProjectPodKey;
import de.mhus.vance.brain.workspace.access.WorkspaceAccessProperties;
import de.mhus.vance.brain.workspace.access.WorkspaceRoutingCache;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Web-UI-facing executions endpoints. Resolves the project's owner pod
 * via {@link WorkspaceRoutingCache} (shared with the workspace path —
 * it's project-pod resolution, not workspace-specific) and proxies to
 * that pod's {@link ExecutionsInternalController}. The Layer-2 hop
 * happens even when the local pod is the owner so dev and prod
 * exercise the same code path; flip
 * {@code vance.workspace.access.bypass-proxy=true} to call the
 * registry / router directly (test mode only).
 *
 * <p>Live updates: not in v1. The Insights "Executions" tab loads a
 * snapshot via {@code /list} and refreshes manually — same convention
 * as the other Insights tabs ({@code recipes}, {@code tools},
 * {@code workspace}).
 */
@RestController
@RequestMapping("/brain/{tenant}/projects/{project}/executions")
@Slf4j
public class ExecutionsController {

    private final ExecutionRegistryService registry;
    private final ExecutionRouter router;
    private final WorkspaceRoutingCache routingCache;
    private final WorkspaceAccessProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RequestAuthority authority;
    private final String internalToken;

    public ExecutionsController(ExecutionRegistryService registry,
                                ExecutionRouter router,
                                WorkspaceRoutingCache routingCache,
                                WorkspaceAccessProperties properties,
                                ObjectMapper objectMapper,
                                RequestAuthority authority,
                                @Value("${vance.internal.token:}") String internalToken) {
        this.registry = registry;
        this.router = router;
        this.routingCache = routingCache;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authority = authority;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    @GetMapping("/list")
    public List<ExecutionInsightsDto> list(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(value = "onlyRunning", required = false, defaultValue = "false") boolean onlyRunning,
            @RequestParam(value = "ownerLabel", required = false) @Nullable String ownerLabel,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        if (properties.isBypassProxy()) {
            return listDirect(tenant, project, onlyRunning, ownerLabel);
        }
        ProjectPodKey key = new ProjectPodKey(tenant, project);
        StringBuilder qs = new StringBuilder();
        if (onlyRunning) qs.append("onlyRunning=true");
        if (ownerLabel != null && !ownerLabel.isBlank()) {
            if (qs.length() > 0) qs.append('&');
            qs.append("ownerLabel=").append(URLEncoder.encode(ownerLabel, StandardCharsets.UTF_8));
        }
        String path = buildInternalPath(tenant, project, "list") + (qs.length() == 0 ? "" : "?" + qs);
        String body = proxyGet(key, path);
        try {
            return objectMapper.readValue(body, new TypeReference<List<ExecutionInsightsDto>>() {});
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse execution list from owner pod", e);
        }
    }

    @GetMapping("/{id}/stat")
    public ExecutionInsightsDto stat(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        if (properties.isBypassProxy()) {
            return statDirect(tenant, project, id);
        }
        ProjectPodKey key = new ProjectPodKey(tenant, project);
        String body = proxyGet(key, buildInternalPath(tenant, project, id + "/stat"));
        try {
            return objectMapper.readValue(body, ExecutionInsightsDto.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse execution stat from owner pod", e);
        }
    }

    @GetMapping("/{id}/tail")
    public ExecutionTailDto tail(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("id") String id,
            @RequestParam(value = "n", required = false, defaultValue = "100") int n,
            @RequestParam(value = "stream", required = false, defaultValue = "stdout") String stream,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.READ);
        if (n <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'n' must be > 0");
        }
        if (properties.isBypassProxy()) {
            return tailDirect(tenant, id, n, stream);
        }
        ProjectPodKey key = new ProjectPodKey(tenant, project);
        StringBuilder qs = new StringBuilder("n=").append(n);
        qs.append("&stream=").append(URLEncoder.encode(stream, StandardCharsets.UTF_8));
        String path = buildInternalPath(tenant, project, id + "/tail") + "?" + qs;
        String body = proxyGet(key, path);
        try {
            return objectMapper.readValue(body, ExecutionTailDto.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse execution tail from owner pod", e);
        }
    }

    // ------------------------------------------------------------------
    // Bypass path — direct registry / router calls (test/dev only)
    // ------------------------------------------------------------------

    private List<ExecutionInsightsDto> listDirect(
            String tenant, String project, boolean onlyRunning, @Nullable String ownerLabel) {
        ExecutionScopeFilter filter = new ExecutionScopeFilter(
                tenant, project, null, null,
                ownerLabel == null || ownerLabel.isBlank() ? null : ownerLabel,
                onlyRunning);
        List<ExecutionRegistryEntry> entries = registry.list(filter);
        List<ExecutionInsightsDto> out = new ArrayList<>(entries.size());
        for (ExecutionRegistryEntry e : entries) out.add(ExecutionInsightsMapper.toDto(e));
        return out;
    }

    private ExecutionInsightsDto statDirect(String tenant, String project, String id) {
        Optional<ExecutionRegistryEntry> hit = registry.find(id);
        if (hit.isEmpty()
                || (hit.get().tenantId() != null && !hit.get().tenantId().equals(tenant))
                || (hit.get().projectId() != null && !hit.get().projectId().equals(project))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown execution: '" + id + "'");
        }
        return ExecutionInsightsMapper.toDto(hit.get());
    }

    private ExecutionTailDto tailDirect(String tenant, String id, int n, String stream) {
        Map<String, Object> result;
        try {
            result = router.tail(id, tenant, n, stream);
        } catch (ToolException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.startsWith("Unknown execution") || msg.contains("not connected")
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            throw new ResponseStatusException(status, msg, e);
        }
        @SuppressWarnings("unchecked")
        List<String> lines = result.get("lines") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList()
                : List.of();
        String resolvedStream = result.get("stream") instanceof String s ? s : stream;
        return ExecutionTailDto.builder()
                .id(id)
                .stream(resolvedStream)
                .requested(n)
                .lines(new ArrayList<>(lines))
                .build();
    }

    // ------------------------------------------------------------------
    // Proxy path — HTTP hop to the owner pod's Layer 2
    // ------------------------------------------------------------------

    private String proxyGet(ProjectPodKey key, String pathAndQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String podEndpoint = resolveOrThrow(key, attempt);
            URI uri = URI.create("http://" + podEndpoint + pathAndQuery);
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
                    log.warn("Executions proxy gave up after retry for {} {}: {}",
                            key.tenantId(), key.projectName(), e.toString());
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Project owner pod unreachable", e);
                }
                log.debug("Executions proxy connect failed (attempt {}); retrying after cache refresh: {}",
                        attempt + 1, e.toString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Executions proxy I/O error", e);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Executions proxy exhausted retries");
    }

    private String resolveOrThrow(ProjectPodKey key, int attempt) {
        Optional<String> ip = attempt == 0 ? routingCache.lookup(key) : routingCache.refresh(key);
        return ip.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Project '" + key.tenantId() + "/" + key.projectName()
                        + "' is not claimed by any pod yet"));
    }

    private static String buildInternalPath(String tenant, String project, String op) {
        return "/internal/executions/"
                + URLEncoder.encode(tenant, StandardCharsets.UTF_8)
                + "/"
                + URLEncoder.encode(project, StandardCharsets.UTF_8)
                + "/"
                + op;
    }

    private static ResponseStatusException mapInternalStatus(int code, String body) {
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new ResponseStatusException(status, body == null ? status.getReasonPhrase() : body);
    }

}
