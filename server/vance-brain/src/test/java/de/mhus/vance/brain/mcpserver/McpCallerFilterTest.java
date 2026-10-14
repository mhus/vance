package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpCallerFilterTest {

    private static final McpCaller ACCOUNT = McpCaller.account("alice");
    private static final McpCaller TOKEN = McpCaller.integration("alice", "tok-1");
    private static final McpCaller OAUTH = McpCaller.oauth("alice", "g1");
    private static final McpCaller OAUTH_BOB = McpCaller.oauth("bob", "g2");

    @Test
    void any_matchesEveryone() {
        assertThat(McpCallerFilter.ANY.matches(ACCOUNT)).isTrue();
        assertThat(McpCallerFilter.ANY.matches(TOKEN)).isTrue();
        assertThat(McpCallerFilter.ANY.matches(OAUTH)).isTrue();
    }

    @Test
    void token_matchesOnlyThatIntegrationToken() {
        McpCallerFilter f = new McpCallerFilter("tok-1", null, List.of());
        assertThat(f.matches(TOKEN)).isTrue();
        assertThat(f.matches(McpCaller.integration("alice", "tok-2"))).isFalse();
        assertThat(f.matches(ACCOUNT)).isFalse();
        // a grant id that happens to equal a token id is still not that token
        assertThat(f.matches(McpCaller.oauth("alice", "tok-1"))).isFalse();
    }

    @Test
    void oauthTrue_onlyOAuth_oauthFalse_everyoneElse() {
        McpCallerFilter only = new McpCallerFilter(null, true, List.of());
        McpCallerFilter none = new McpCallerFilter(null, false, List.of());
        assertThat(only.matches(OAUTH)).isTrue();
        assertThat(only.matches(TOKEN)).isFalse();
        assertThat(only.matches(ACCOUNT)).isFalse();
        assertThat(none.matches(OAUTH)).isFalse();
        assertThat(none.matches(TOKEN)).isTrue();
        assertThat(none.matches(ACCOUNT)).isTrue();
    }

    @Test
    void users_restrictTheAccount() {
        McpCallerFilter f = new McpCallerFilter(null, true, List.of("alice"));
        assertThat(f.matches(OAUTH)).isTrue();
        assertThat(f.matches(OAUTH_BOB)).isFalse();
    }

    @Test
    void tokenWithOauthTrue_isAConfigError() {
        assertThatThrownBy(() -> new McpCallerFilter("tok-1", true, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
