package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ──────────────── parse: a written filter that reads as nothing is an error ────────────────

    @Test
    void parse_absentUsers_meansAnyAccount() {
        McpCallerFilter f = McpCallerFilter.parse(spec(), "e");
        assertThat(f.users()).isEmpty();
        assertThat(f.matches(OAUTH_BOB)).isTrue();
    }

    @Test
    void parse_explicitlyEmptyUsers_meansAnyAccount() {
        McpCallerFilter f = McpCallerFilter.parse(spec("users", List.of()), "e");
        assertThat(f.users()).isEmpty();
    }

    @Test
    void parse_usersWithoutValue_meansAnyAccount() {
        McpCallerFilter f = McpCallerFilter.parse(spec("users", null), "e");
        assertThat(f.users()).isEmpty();
    }

    @Test
    void parse_usersOfOnlyInvalidEntries_failsClosed() {
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("users", Arrays.asList((Object) null)), "e"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("users");
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("users", List.of("", "  ")), "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("users", List.of(42, true)), "e"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parse_usersWithSomeInvalidEntries_keepsTheValidOnes() {
        McpCallerFilter f = McpCallerFilter.parse(spec("users", Arrays.asList("alice", null, 42, "")), "e");
        assertThat(f.users()).containsExactly("alice");
        assertThat(f.matches(OAUTH_BOB)).isFalse();
    }

    @Test
    void parse_blankSingleUser_failsClosed() {
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("users", " "), "e"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parse_singleUserString_isAccepted() {
        assertThat(McpCallerFilter.parse(spec("users", "alice"), "e").users()).containsExactly("alice");
    }

    @Test
    void parse_invalidToken_failsClosed() {
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("token", ""), "e"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("token", null), "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("token", 123), "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> McpCallerFilter.parse(spec("token", new ArrayList<>(List.of("tok-1"))), "e"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parse_validToken_pinsTheEntry() {
        McpCallerFilter f = McpCallerFilter.parse(spec("token", " tok-1 "), "e");
        assertThat(f.token()).isEqualTo("tok-1");
        assertThat(f.matches(TOKEN)).isTrue();
        assertThat(f.matches(ACCOUNT)).isFalse();
    }

    private static Map<String, Object> spec(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
