package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
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
 * MCP client configures a 24h access token (service-account login), an
 * integration token of the {@code mcp} profile, or logs in through the OAuth
 * server. The narrowed credentials additionally pass
 * {@link McpGlobalAccessService}.
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
    private final McpGlobalAccessService globalAccess;

    @PostMapping(value = "/brain/{tenant}/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestBody String body,
            HttpServletRequest request) {

        ResponseEntity<?> pinned = refusePinnedCredential(request, tenant);
        if (pinned != null) {
            return pinned;
        }

        String project =
                (projectId == null || projectId.isBlank()) ? HomeBootstrapService.TENANT_PROJECT_NAME : projectId;

        // Endpoint-level gate: the caller must at least READ the target
        // project. Per-tool EXECUTE checks run inside ToolDispatcher.
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        // A narrowed credential (OAuth grant for this door, integration token
        // of the `mcp` profile) additionally needs an entry in the tenant's
        // mcp-global-access.yaml — the tenant admin decides who may hand the
        // full catalogue to an outside program. The full-account login token
        // is not governed by it.
        McpCaller caller = McpCaller.from(request);
        if (caller.narrowed() && globalAccess.resolveEntry(tenant, caller) == null) {
            log.debug(
                    "MCP global door denied for narrowed {} caller '{}' in tenant '{}'",
                    caller.kind(),
                    caller.username(),
                    tenant);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("mcp access denied: no entry in " + McpGlobalAccessService.CONFIG_PATH
                            + " of the _tenant project admits this caller\n");
        }

        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        McpProtocol.Outcome outcome = service.handle(body, tenant, project, username);
        if (outcome.noContent()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(outcome.body());
    }

    /** No server-push SSE stream — the spec allows answering GET with 405. */
    @GetMapping("/brain/{tenant}/mcp")
    public ResponseEntity<?> noStream(@PathVariable("tenant") String tenant, HttpServletRequest request) {
        ResponseEntity<?> pinned = refusePinnedCredential(request, tenant);
        if (pinned != null) {
            return pinned;
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * A {@code 403} for a credential pinned to a project ({@code pid}), else
     * {@code null}.
     *
     * <p>The pin cannot be honoured behind this door: {@code tools/call} runs
     * through {@code ToolDispatcher}, which builds the tool's security context
     * from tenant and user alone, so a pinned credential would come out the
     * other side unconfined. The scope-profile check already refuses a pinned
     * token of the tenant-wide {@code mcp} profile; this is the door's own
     * guard for every other pinned credential type (e.g. a script-run token).
     */
    private static @Nullable ResponseEntity<?> refusePinnedCredential(HttpServletRequest request, String tenant) {
        if (!(request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims)) {
            return null;
        }
        String pin = claims.projectId();
        if (pin == null || pin.isBlank()) {
            return null;
        }
        log.debug(
                "MCP global door denied a {} credential pinned to project '{}' (user '{}', tenant '{}')",
                claims.tokenType(),
                pin,
                claims.username(),
                tenant);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_PLAIN)
                .body("mcp access denied: a project-pinned credential cannot use the global MCP door — "
                        + "use the project door /mcp/{project}\n");
    }
}
