package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for the global MCP door — {@code POST /mcp}, nothing else.
 *
 * <p>Until this profile existed, the global door only took the full-account
 * login token. A credential carrying {@code mcp} reaches this one route; the
 * full tool catalogue behind it is still bounded by the account's grants, and
 * who may hold such a credential at all is decided by the tenant admin in
 * {@code _vance/config/mcp-global-access.yaml} ({@link McpGlobalAccessService}).
 *
 * <p>{@code requiresProject() = false}: the door is tenant-wide, the target
 * project is a request parameter checked per call against the grants.
 */
@Component
public class McpGlobalScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "mcp";
    }

    @Override
    public String label() {
        return "Global MCP tool access";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(IntegrationSurface.of("POST", "/mcp"), IntegrationSurface.of("GET", "/mcp"));
    }

    @Override
    public boolean requiresProject() {
        return false;
    }
}
