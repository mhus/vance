package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for the project-scoped MCP surface — the credential an
 * external coding agent holds when it works on a project's documents.
 *
 * <p>Exactly the project route, nothing else. The global MCP endpoint
 * ({@code POST /mcp}) is deliberately <b>not</b> declared here: a token
 * of this profile cannot reach the full tool catalogue, because the
 * surface check is fail-closed — an integration token only passes
 * routes that at least one of its profiles explicitly names. That is
 * the route separation between the two MCP doors, and it lives in this
 * list, not in a permission grant (grants are resource-based, never
 * route-based).
 *
 * <p>{@code GET} is declared alongside {@code POST} on purpose: the
 * route answers GET with the spec-conform {@code 405} (no SSE stream),
 * and a transport-probing client should see that protocol answer rather
 * than a filter rejection that looks like an auth problem.
 *
 * <p>{@code requiresProject} is the interface default — every token of
 * this profile is pinned to one project, and the endpoint's
 * {@code READ} enforcement checks the pin against the route's project
 * segment on every request.
 */
@Component
public class McpProjectScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "mcp-project";
    }

    @Override
    public String label() {
        return "Project-scoped MCP document access";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(IntegrationSurface.of("POST", "/mcp/*"), IntegrationSurface.of("GET", "/mcp/*"));
    }
}
