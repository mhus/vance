package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.api.servertools.ServerToolOAuthDto;
import de.mhus.vance.api.servertools.ServerToolOAuthStartDto;
import de.mhus.vance.brain.oauth.OAuthFlowService;
import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.ResolvedOAuthProvider;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.types.ToolPackUnavailableException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login endpoints for MCP server tools with {@code auth.type: oauth}.
 *
 * <ul>
 *   <li>{@code POST mcp/prepare?project&tool} — makes sure the server's
 *       OAuth provider exists (discovery + client registration on first
 *       use) and returns the login state. The UI calls it before navigating,
 *       so a discovery failure shows as a message, not as an error page.</li>
 *   <li>{@code POST mcp/init?project&tool&returnTo} — starts the code flow
 *       for the account the tool's login scope selects and returns
 *       a {@code ServerToolOAuthStartDto}; the UI navigates there. A POST, not
 *       a navigable GET: starting a login has side effects (discovery,
 *       client registration, a provider document in {@code _tenant}) and
 *       must not be triggerable cross-site through the SameSite=Lax session
 *       cookie, which a top-level GET navigation would carry. The callback
 *       is the shared {@code _mcp/callback} of {@code OAuthController}.</li>
 *   <li>{@code DELETE mcp/connection?project&tool} — removes that login.</li>
 *   <li>{@code GET client-metadata.json} — this brain's Client ID Metadata
 *       Document; public, fetched by authorization servers.</li>
 * </ul>
 *
 * <p>Permission follows the login scope: READ on the project for a
 * personal login, ADMIN on the project or tenant for a shared account.
 */
@RestController
@RequestMapping("/brain/{tenant}/oauth-client")
@RequiredArgsConstructor
@Slf4j
public class McpOAuthController {

    private final McpOAuthService service;
    private final OAuthFlowService flowService;
    private final RequestAuthority authority;

    @GetMapping(value = "/client-metadata.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clientMetadata(@PathVariable("tenant") String tenant) {
        return service.clientMetadataDocument(tenant);
    }

    @PostMapping("/mcp/prepare")
    public ServerToolOAuthDto prepare(
            @PathVariable("tenant") String tenant,
            @RequestParam("project") String project,
            @RequestParam("tool") String tool,
            HttpServletRequest request) {
        McpOAuthService.McpServerTool server = authorizedTool(tenant, project, tool, request);
        SecurityContext ctx = authority.contextOf(request);
        OAuthTokenOwner owner = ownerOf(server, project, ctx.subjectId());
        try {
            service.ensureProvider(tenant, project, server, WriteActor.system(ctx));
        } catch (McpOAuthException e) {
            log.info(
                    "MCP OAuth prepare failed: tenant='{}' project='{}' tool='{}': {}",
                    tenant,
                    project,
                    tool,
                    e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
        return ServerToolOAuthDto.builder()
                .providerId(server.settings().providerId())
                .loginScope(server.settings().loginScope().wireName())
                .status(service.status(tenant, server.settings(), owner))
                .canConnect(true)
                .build();
    }

    @PostMapping(value = "/mcp/init", produces = MediaType.APPLICATION_JSON_VALUE)
    public ServerToolOAuthStartDto init(
            @PathVariable("tenant") String tenant,
            @RequestParam("project") String project,
            @RequestParam("tool") String tool,
            @RequestParam(value = "returnTo", required = false) @Nullable String returnTo,
            HttpServletRequest request) {
        McpOAuthService.McpServerTool server = authorizedTool(tenant, project, tool, request);
        SecurityContext ctx = authority.contextOf(request);
        OAuthTokenOwner owner = ownerOf(server, project, ctx.subjectId());
        URI target;
        try {
            ResolvedOAuthProvider provider = service.ensureProvider(tenant, project, server, WriteActor.system(ctx));
            target = flowService.start(tenant, ctx.subjectId(), provider, owner, returnTo);
        } catch (McpOAuthException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        } catch (RuntimeException e) {
            log.warn(
                    "MCP OAuth init failed: tenant='{}' project='{}' tool='{}': {}",
                    tenant,
                    project,
                    tool,
                    e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "could not start the MCP login", e);
        }
        return ServerToolOAuthStartDto.builder()
                .authorizationUrl(target.toString())
                .build();
    }

    @DeleteMapping("/mcp/connection")
    public ResponseEntity<Void> disconnect(
            @PathVariable("tenant") String tenant,
            @RequestParam("project") String project,
            @RequestParam("tool") String tool,
            HttpServletRequest request) {
        McpOAuthService.McpServerTool server = authorizedTool(tenant, project, tool, request);
        OAuthTokenOwner owner =
                ownerOf(server, project, authority.contextOf(request).subjectId());
        service.disconnect(tenant, server.settings(), owner);
        log.info(
                "MCP OAuth disconnected: tenant='{}' project='{}' tool='{}' owner='{}'",
                tenant,
                project,
                tool,
                owner.storageProjectId());
        return ResponseEntity.noContent().build();
    }

    /** The account the login scope selects; 400 for a project login in {@code _tenant}. */
    private static OAuthTokenOwner ownerOf(McpOAuthService.McpServerTool server, String project, String userId) {
        try {
            return server.settings().owner(project, userId);
        } catch (ToolPackUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private McpOAuthService.McpServerTool authorizedTool(
            String tenant, String project, String tool, HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        McpOAuthService.McpServerTool server;
        try {
            server = service.resolveTool(tenant, project, tool);
        } catch (McpOAuthException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
        authority.enforce(
                request,
                McpOAuthService.loginResource(server.settings(), tenant, project),
                McpOAuthService.loginAction(server.settings()));
        return server;
    }
}
