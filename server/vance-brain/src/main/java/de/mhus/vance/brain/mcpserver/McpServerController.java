package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP server endpoint: exposes Vance's tool catalogue to external MCP
 * clients (Claude Code, Cursor, …) over Streamable HTTP.
 *
 * <p>One {@code POST /brain/{tenant}/mcp} carries JSON-RPC 2.0 frames
 * ({@code initialize}, {@code tools/list}, {@code tools/call}). The
 * {@code tenant} path segment is validated against the JWT by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter}; the optional
 * {@code projectId} query-parameter (business name) scopes the tool
 * catalogue and defaults to the tenant-wide system project
 * ({@link HomeBootstrapService#TENANT_PROJECT_NAME}).
 *
 * <p>Auth is the standard {@code Authorization: Bearer <jwt>} chain — an
 * MCP client configures a 24h access token (service-account login).
 * Per-invocation authorization is enforced downstream by
 * {@link de.mhus.vance.brain.tools.ToolDispatcher}; the endpoint itself
 * requires {@code READ} on the target project.
 *
 * <p>No server-initiated SSE stream is offered, so {@code GET} returns
 * {@code 405} as the MCP spec permits.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class McpServerController {

    private final McpServerService service;
    private final RequestAuthority authority;

    @PostMapping(
            value = "/brain/{tenant}/mcp",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestBody String body,
            HttpServletRequest request) {

        String project = (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME
                : projectId;

        // Endpoint-level gate: the caller must at least READ the target
        // project. Per-tool EXECUTE checks run inside ToolDispatcher.
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        McpServerService.Outcome outcome = service.handle(body, tenant, project, username);
        if (outcome.noContent()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(outcome.body());
    }

    /** No server-push SSE stream — the spec allows answering GET with 405. */
    @GetMapping("/brain/{tenant}/mcp")
    public ResponseEntity<Void> noStream(@PathVariable("tenant") String tenant) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
