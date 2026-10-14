package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpProjectAccessConfigTest {

    private static final String VALID = """
            default: deny
            access:
              - name: claude-code
                token: "tok-123"
                mode: rw
                paths: ["spec", "readme/"]
              - name: humans
                mode: ro
            """;

    @Test
    void parse_readsEntries_andNormalizesPrefixes() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse(VALID);

        assertThat(config.access()).hasSize(2);

        McpProjectAccessConfig.Entry pinned = config.access().get(0);
        assertThat(pinned.name()).isEqualTo("claude-code");
        assertThat(pinned.token()).isEqualTo("tok-123");
        assertThat(pinned.mode()).isEqualTo(McpProjectAccessConfig.Mode.RW);
        // "spec" is normalized to the folder form, "readme/" stays
        assertThat(pinned.paths()).containsExactly("spec/", "readme/");

        McpProjectAccessConfig.Entry open = config.access().get(1);
        assertThat(open.token()).isNull();
        assertThat(open.mode()).isEqualTo(McpProjectAccessConfig.Mode.RO);
        assertThat(open.isWholeProject()).isTrue();
    }

    @Test
    void parse_leadingSlashesInPrefixes_areNormalizedAway() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse("""
                access:
                  - name: x
                    mode: ro
                    paths: ["/spec/"]
                """);

        assertThat(config.access().get(0).paths()).containsExactly("spec/");
    }

    @Test
    void parse_missingAccessList_failsClosed() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("default: deny\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access");
    }

    @Test
    void parse_nonDenyDefault_isRejected() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("default: allow\naccess:\n  - name: x\n    mode: ro\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deny");
    }

    @Test
    void parse_unknownMode_isRejected() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - name: x
                    mode: admin
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mode");
    }

    @Test
    void parse_missingName_isRejected() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - mode: ro
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void resolve_firstMatchingEntryWins_andTokenPinsApply() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse(VALID);

        // Pinned entry applies to its token …
        assertThat(config.resolve(McpCaller.integration("alice", "tok-123")).name())
                .isEqualTo("claude-code");
        // … and to nobody else — a different token falls through to the
        // token-less entry.
        assertThat(config.resolve(McpCaller.integration("alice", "tok-999")).name())
                .isEqualTo("humans");
        // Humans (no token id) also land on the token-less entry.
        assertThat(config.resolve(McpCaller.account("alice")).name()).isEqualTo("humans");

        // First-match-wins: a token-less entry before a pinned one
        // shadows it for everyone.
        McpProjectAccessConfig ordered = new McpProjectAccessConfig(List.of(
                new McpProjectAccessConfig.Entry(
                        "ro-first", McpCallerFilter.ANY, McpProjectAccessConfig.Mode.RO, List.of()),
                new McpProjectAccessConfig.Entry(
                        "pinned",
                        new McpCallerFilter("tok-1", null, List.of()),
                        McpProjectAccessConfig.Mode.RW,
                        List.of())));
        assertThat(ordered.resolve(McpCaller.integration("alice", "tok-1")).name())
                .isEqualTo("ro-first");
    }

    @Test
    void resolve_noMatch_isDeny() {
        assertThat(new McpProjectAccessConfig(List.of()).resolve(McpCaller.integration("alice", "tok-1")))
                .isNull();
        assertThat(McpProjectAccessConfig.EMPTY.resolve(McpCaller.account("alice")))
                .isNull();
    }

    @Test
    void pathAllowed_matchesPrefixSubtrees_only() {
        McpProjectAccessConfig.Entry entry = new McpProjectAccessConfig.Entry(
                "x", McpCallerFilter.ANY, McpProjectAccessConfig.Mode.RO, List.of("spec/"));

        assertThat(entry.pathAllowed("spec/design.md")).isTrue();
        assertThat(entry.pathAllowed("spec/sub/deep.md")).isTrue();
        assertThat(entry.pathAllowed("specify.md")).isFalse(); // prefix, not string head
        assertThat(entry.pathAllowed("readme/x.md")).isFalse();
        assertThat(entry.pathAllowed("/spec/x.md")).isTrue(); // leading slash normalized
    }

    @Test
    void parse_oauthAndUsers_selectOAuthCallersOfNamedAccounts() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse("""
                default: deny
                access:
                  - name: claude-code-mike
                    oauth: true
                    users: [mike]
                    mode: rw
                    paths: ["spec/"]
                  - name: no-oauth
                    oauth: false
                    mode: ro
                """);

        assertThat(config.resolve(McpCaller.oauth("mike", "g1")).name()).isEqualTo("claude-code-mike");
        assertThat(config.resolve(McpCaller.oauth("bob", "g2"))).isNull();
        assertThat(config.resolve(McpCaller.account("bob")).name()).isEqualTo("no-oauth");
    }

    @Test
    void parse_tokenlessEntry_admitsOAuthCallersToo() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse(VALID);

        assertThat(config.resolve(McpCaller.oauth("anyone", "g1")).name()).isEqualTo("humans");
        assertThat(config.resolve(McpCaller.integration("anyone", "tok-123")).name())
                .isEqualTo("claude-code");
    }

    @Test
    void parse_tokenTogetherWithOauthTrue_failsClosed() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - name: broken
                    token: "tok-1"
                    oauth: true
                    mode: ro
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void parse_nonBooleanOauth_failsClosed() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - name: x
                    oauth: "yes"
                    mode: ro
                """)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parse_usersListOfOnlyInvalidEntries_failsClosed() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - name: typo
                    users: [42, ~]
                    mode: rw
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typo");
    }

    @Test
    void parse_explicitlyEmptyUsersList_admitsAnyAccount() {
        McpProjectAccessConfig config = McpProjectAccessConfig.parse("""
                access:
                  - name: everyone
                    users: []
                    mode: ro
                """);

        assertThat(config.resolve(McpCaller.account("bob")).name()).isEqualTo("everyone");
    }

    @Test
    void parse_nonStringToken_failsClosed() {
        assertThatThrownBy(() -> McpProjectAccessConfig.parse("""
                access:
                  - name: bot
                    token: 123
                    mode: ro
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }
}
