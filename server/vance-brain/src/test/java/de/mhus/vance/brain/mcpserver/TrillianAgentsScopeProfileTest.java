package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.access.IntegrationSurface;
import org.junit.jupiter.api.Test;

/**
 * The counterpart credential reaches the agents door and nothing else: the
 * surface check is fail-closed, so whatever this profile does not name, a
 * token with it cannot call — above all the global MCP endpoint.
 */
class TrillianAgentsScopeProfileTest {

    private final TrillianAgentsScopeProfile profile = new TrillianAgentsScopeProfile();

    @Test
    void idIsAgents_andTheDoorIsTenantWide() {
        assertThat(profile.id()).isEqualTo("agents");
        assertThat(profile.requiresProject()).isFalse();
    }

    @Test
    void coversPostAndGetOnTheDoor() {
        assertThat(covers("POST", "/agents")).isTrue();
        assertThat(covers("GET", "/agents")).isTrue();
    }

    @Test
    void coversNothingElse() {
        assertThat(covers("POST", "/mcp")).isFalse();
        assertThat(covers("POST", "/mcp/some-project")).isFalse();
        assertThat(covers("GET", "/documents")).isFalse();
        assertThat(covers("DELETE", "/agents")).isFalse();
        assertThat(covers("POST", "/agents/extra")).isFalse();
    }

    private boolean covers(String method, String path) {
        return profile.surfaces().stream().anyMatch((IntegrationSurface s) -> s.matches(method, path));
    }
}
