package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-scoped MCP endpoint: the narrow, config-regulated surface for
 * external agents — a coding agent working on a project's documents, not
 * the full tool tree. Route {@code POST /brain/{tenant}/mcp/{project}}
 * carries the same JSON-RPC 2.0 frames as the global
 * {@link McpServerController}; what differs is the surface behind them
 * (fixed {@code doc_*} inventory, per-project access config at
 * {@code _vance/config/mcp-access.yaml}).
 *
 * <p>Auth is the standard filter chain — an integration token carrying
 * the {@code mcp-project} scope profile (project-pinned, surfaces
 * exactly this route), or a normal Bearer whose grants apply. The
 * endpoint gate enforces {@code READ} on the target project; further
 * authorization is per call: the config entry's mode and path prefixes,
 * and the write tools' own per-document WRITE enforcement.
 *
 * <p>No server-initiated SSE stream, so {@code GET} returns {@code 405}
 * as the MCP spec permits — the route is declared in the scope profile
 * with both methods so a transport-probing client gets the spec-conform
 * 405 instead of a filter rejection.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class McpProjectController {

    private final McpProjectService service;
    private final RequestAuthority authority;

    @PostMapping(value = "/brain/{tenant}/mcp/{project}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestBody String body,
            HttpServletRequest request) {

        // Endpoint-level gate: the caller must at least READ the target
        // project — also the project pin check for integration tokens.
        // Everything finer is per call inside McpProjectService.
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        // The caller decides which entries of mcp-access.yaml apply: per-token
        // entries match an integration token's jti, `oauth` entries match
        // door-bound OAuth grants, `users` the account behind either.
        McpProtocol.Outcome outcome = service.handle(body, tenant, project, McpCaller.from(request));
        if (outcome.noContent()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(outcome.body());
    }

    /** No server-push SSE stream — the spec allows answering GET with 405. */
    @GetMapping("/brain/{tenant}/mcp/{project}")
    public ResponseEntity<Void> noStream(
            @PathVariable("tenant") String tenant, @PathVariable("project") String project) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
