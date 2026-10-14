package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpGlobalAccessConfigTest {

    @Test
    void parse_andResolve_firstMatchWins() {
        McpGlobalAccessConfig config = McpGlobalAccessConfig.parse("""
                default: deny
                access:
                  - name: mike-claude
                    oauth: true
                    users: [mike]
                  - name: ci-bot
                    token: "tok-9"
                """);

        assertThat(config.resolve(McpCaller.oauth("mike", "g1")).name()).isEqualTo("mike-claude");
        assertThat(config.resolve(McpCaller.oauth("bob", "g2"))).isNull();
        assertThat(config.resolve(McpCaller.integration("ci", "tok-9")).name()).isEqualTo("ci-bot");
        assertThat(config.resolve(McpCaller.integration("ci", "tok-8"))).isNull();
    }

    @Test
    void empty_deniesEveryone() {
        assertThat(McpGlobalAccessConfig.EMPTY.resolve(McpCaller.oauth("mike", "g1")))
                .isNull();
    }

    @Test
    void modeOrPaths_belongToTheProjectDoor_andFailTheParse() {
        assertThatThrownBy(() -> McpGlobalAccessConfig.parse("""
                access:
                  - name: x
                    oauth: true
                    mode: rw
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp-access.yaml");
    }

    @Test
    void defaultOtherThanDeny_failsTheParse() {
        assertThatThrownBy(() -> McpGlobalAccessConfig.parse("default: allow\naccess: []\n"))
                .isInstanceOf(IllegalStateException.class);
    }
}
