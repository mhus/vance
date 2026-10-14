package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.brain.mcpserver.McpCaller;
import de.mhus.vance.brain.mcpserver.McpGlobalAccessService;
import de.mhus.vance.brain.mcpserver.McpProjectAccessService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides at consent time whether a grant for a door may be created at all.
 *
 * <p>The same rules apply again on every call — this check exists so that a
 * user is told "no" on the consent page rather than handed a token that then
 * fails every request. A grant without access is a bad thing to give a
 * program: it logs in fine and then loses every call.
 *
 * <ul>
 *   <li>{@code mcp-project}: READ on the project, and an entry in the
 *       project's {@code mcp-access.yaml} that admits an OAuth caller of this
 *       account;</li>
 *   <li>{@code mcp}: an entry in the tenant's {@code mcp-global-access.yaml};</li>
 *   <li>{@code agents}: READ on the tenant — which Trillian answers is the
 *       exposure gate's business per conversation;</li>
 *   <li>{@code account}: no door, nothing to check — first-party only.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OAuthDoorGate {

    private final PermissionService permissionService;
    private final SecurityContextFactory securityContextFactory;
    private final McpProjectAccessService projectAccess;
    private final McpGlobalAccessService globalAccess;

    /** A human-readable reason the grant cannot be given, or {@code null} when it can. */
    public @Nullable String denial(String tenant, String user, @Nullable OAuthDoor door) {
        if (door == null) {
            return null;
        }
        SecurityContext subject = securityContextFactory.forToolSubject(tenant, user);
        McpCaller caller = new McpCaller(McpCaller.Kind.OAUTH, null, user);
        return switch (door.kind()) {
            case MCP_PROJECT -> {
                String project = door.project();
                if (project == null
                        || !permissionService.check(subject, new Resource.Project(tenant, project), Action.READ)) {
                    yield "You have no read access to project '" + project + "'.";
                }
                if (projectAccess.resolveEntry(tenant, project, caller) == null) {
                    yield "Project '" + project + "' does not admit OAuth clients for your account ("
                            + McpProjectAccessService.CONFIG_PATH + ").";
                }
                yield null;
            }
            case MCP ->
                globalAccess.resolveEntry(tenant, caller) == null
                        ? "The tenant does not admit OAuth clients for your account at the global MCP door ("
                                + McpGlobalAccessService.CONFIG_PATH + ")."
                        : null;
            case AGENTS ->
                permissionService.check(subject, new Resource.Tenant(tenant), Action.READ)
                        ? null
                        : "You have no access to this tenant.";
        };
    }
}
