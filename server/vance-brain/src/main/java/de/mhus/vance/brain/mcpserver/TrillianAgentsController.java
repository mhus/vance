package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
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
 * The agents door: a dedicated MCP surface for external counterparts of
 * exposed Trillians, at {@code POST /brain/{tenant}/agents} — a separate
 * route on purpose, not part of the global MCP server: that one carries the
 * full tool catalogue and is too powerful to hand out, this one carries
 * exactly four conversation tools
 * ({@link TrillianAgentsToolCatalog}). The route needs no "mcp" in its
 * name — MCP is the protocol, not the address.
 *
 * <p>Auth is the standard filter chain — an integration token carrying the
 * {@code agents} scope profile (no project pin; surfaces exactly this
 * route), or a normal Bearer whose grants apply. The endpoint gate enforces
 * tenant {@code READ}; the finer gate — who may talk to whom — is the
 * exposure configuration, checked per call inside the protocol entry.
 *
 * <p>No server-initiated SSE stream, so {@code GET} returns {@code 405} as
 * the MCP spec permits — the route is declared in the scope profile with
 * both methods so a transport-probing client gets the spec-conform answer
 * instead of a filter rejection.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TrillianAgentsController {

    private final TrillianAgentsService service;
    private final RequestAuthority authority;

    @PostMapping(value = "/brain/{tenant}/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(
            @PathVariable("tenant") String tenant, @RequestBody String body, HttpServletRequest request) {

        // Endpoint-level gate: the caller must be able to see this tenant at
        // all. Who may open conversations with which Trillian is the per-call
        // exposure gate inside TrillianConversationService.
        authority.enforce(request, new Resource.Tenant(tenant), Action.READ);

        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        McpProtocol.Outcome outcome = service.handle(body, tenant, username);
        if (outcome.noContent()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(outcome.body());
    }

    /** No server-push SSE stream — the spec allows answering GET with 405. */
    @GetMapping("/brain/{tenant}/agents")
    public ResponseEntity<Void> noStream(@PathVariable("tenant") String tenant) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
