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
        assertThat(config.resolve("tok-123").name()).isEqualTo("claude-code");
        // … and to nobody else — a different token falls through to the
        // token-less entry.
        assertThat(config.resolve("tok-999").name()).isEqualTo("humans");
        // Humans (no token id) also land on the token-less entry.
        assertThat(config.resolve(null).name()).isEqualTo("humans");

        // First-match-wins: a token-less entry before a pinned one
        // shadows it for everyone.
        McpProjectAccessConfig ordered = new McpProjectAccessConfig(List.of(
                new McpProjectAccessConfig.Entry("ro-first", null, McpProjectAccessConfig.Mode.RO, List.of()),
                new McpProjectAccessConfig.Entry("pinned", "tok-1", McpProjectAccessConfig.Mode.RW, List.of())));
        assertThat(ordered.resolve("tok-1").name()).isEqualTo("ro-first");
    }

    @Test
    void resolve_noMatch_isDeny() {
        assertThat(new McpProjectAccessConfig(List.of()).resolve("tok-1")).isNull();
        assertThat(McpProjectAccessConfig.EMPTY.resolve(null)).isNull();
    }

    @Test
    void pathAllowed_matchesPrefixSubtrees_only() {
        McpProjectAccessConfig.Entry entry =
                new McpProjectAccessConfig.Entry("x", null, McpProjectAccessConfig.Mode.RO, List.of("spec/"));

        assertThat(entry.pathAllowed("spec/design.md")).isTrue();
        assertThat(entry.pathAllowed("spec/sub/deep.md")).isTrue();
        assertThat(entry.pathAllowed("specify.md")).isFalse(); // prefix, not string head
        assertThat(entry.pathAllowed("readme/x.md")).isFalse();
        assertThat(entry.pathAllowed("/spec/x.md")).isTrue(); // leading slash normalized
    }
}
