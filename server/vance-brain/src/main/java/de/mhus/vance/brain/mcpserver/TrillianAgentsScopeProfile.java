package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for the agents door ({@code POST /brain/{tenant}/agents})
 * — the credential an external counterpart of an exposed Trillian holds.
 *
 * <p>Exactly that route, nothing else: neither the global MCP endpoint nor
 * any REST surface is declared, because the surface check is fail-closed —
 * an integration token only passes routes that at least one of its profiles
 * explicitly names. A counterpart credential must never be one MCP tool
 * away from the full tool catalogue.
 *
 * <p>{@code GET} is declared alongside {@code POST} on purpose: the route
 * answers GET with the spec-conform {@code 405} (no SSE stream), and a
 * transport-probing MCP client should see that protocol answer rather than
 * a filter rejection that looks like an auth problem.
 *
 * <p>{@code requiresProject} is overridden to {@code false}: the door is
 * tenant-wide by design — the conversation target is chosen per call by the
 * Trillian's exposed name, not pinned into the credential.
 */
@Component
public class TrillianAgentsScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "agents";
    }

    @Override
    public String label() {
        return "Exposed Trillian conversation door (agents MCP)";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(IntegrationSurface.of("POST", "/agents"), IntegrationSurface.of("GET", "/agents"));
    }

    @Override
    public boolean requiresProject() {
        return false;
    }
}
