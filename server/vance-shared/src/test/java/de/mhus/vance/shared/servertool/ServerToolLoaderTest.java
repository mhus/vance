package de.mhus.vance.shared.servertool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ServerToolLoader#validateYaml}. Bypasses
 * {@code DocumentService} via the public validate entry point, so this
 * test class needs no Spring context and no Mongo container.
 */
class ServerToolLoaderTest {

    private final ServerToolLoader loader = new ServerToolLoader(/*documentService*/ null);

    @Test
    void parses_minimal_yaml_with_defaults() {
        ServerToolConfig cfg = loader.validateYaml("doc_processes", """
                type: doc_lookup
                description: Processes manual
                parameters:
                  path: manuals/processes.md
                """);

        assertThat(cfg.name()).isEqualTo("doc_processes");
        assertThat(cfg.type()).isEqualTo("doc_lookup");
        assertThat(cfg.description()).isEqualTo("Processes manual");
        assertThat(cfg.parameters()).containsEntry("path", "manuals/processes.md");
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.primary()).isFalse();
        assertThat(cfg.defaultDeferred()).isFalse();
        assertThat(cfg.labels()).isEmpty();
        assertThat(cfg.disabledSubTools()).isEmpty();
        assertThat(cfg.source()).isEqualTo(ServerToolConfig.Source.PROJECT);
    }

    @Test
    void parses_full_yaml_with_all_optional_fields() {
        ServerToolConfig cfg = loader.validateYaml("jira", """
                type: mcp_server
                description: JIRA tools via MCP
                parameters:
                  transport: HTTP
                  url: https://jira.example.com/mcp
                labels: [external, ticketing]
                enabled: true
                primary: true
                defaultDeferred: true
                disabledSubTools:
                  - admin_users
                  - admin_groups
                """);

        assertThat(cfg.labels()).containsExactly("external", "ticketing");
        assertThat(cfg.primary()).isTrue();
        assertThat(cfg.defaultDeferred()).isTrue();
        assertThat(cfg.disabledSubTools()).containsExactlyInAnyOrder("admin_users", "admin_groups");
        assertThat(cfg.parameters()).containsEntry("transport", "HTTP");
    }

    @Test
    void enabled_false_is_honoured() {
        ServerToolConfig cfg = loader.validateYaml("blocked", """
                type: doc_lookup
                description: disabled
                enabled: false
                parameters:
                  path: x.md
                """);
        assertThat(cfg.enabled()).isFalse();
    }

    @Test
    void name_in_yaml_must_match_document_path() {
        assertThatThrownBy(() -> loader.validateYaml("expected_name", """
                name: different_name
                type: doc_lookup
                description: bad
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void name_in_yaml_is_optional_when_path_matches() {
        ServerToolConfig cfg = loader.validateYaml("alpha", """
                name: alpha
                type: doc_lookup
                description: ok
                """);
        assertThat(cfg.name()).isEqualTo("alpha");
    }

    @Test
    void normalises_name_to_lowercase() {
        ServerToolConfig cfg = loader.validateYaml("UpperCase", """
                type: doc_lookup
                description: ok
                """);
        assertThat(cfg.name()).isEqualTo("uppercase");
    }

    @Test
    void rejects_missing_type() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                description: nope
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejects_missing_description() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: doc_lookup
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejects_empty_yaml() {
        assertThatThrownBy(() -> loader.validateYaml("x", "  \n"))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_non_map_root() {
        assertThatThrownBy(() -> loader.validateYaml("x", "- a\n- b\n"))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("top-level map");
    }

    @Test
    void rejects_non_boolean_enabled() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: doc_lookup
                description: bad
                enabled: "yes"
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("enabled");
    }

    @Test
    void rejects_non_list_labels() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: doc_lookup
                description: bad
                labels: not-a-list
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("labels");
    }

    @Test
    void rejects_blank_label() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                type: doc_lookup
                description: bad
                labels: [valid, ""]
                """))
                .isInstanceOf(ServerToolLoader.ServerToolParseException.class)
                .hasMessageContaining("labels");
    }

    @Test
    void path_for_uses_prefix_and_suffix() {
        assertThat(ServerToolLoader.pathFor("Slack-Bot"))
                .isEqualTo("server-tools/slack-bot.yaml");
    }

    @Test
    void carries_raw_yaml_for_round_trip() {
        String yaml = """
                type: doc_lookup
                description: hello
                parameters:
                  path: x.md
                """;
        ServerToolConfig cfg = loader.validateYaml("hello", yaml);
        assertThat(cfg.yaml()).isEqualTo(yaml);
    }

    @Test
    void promptHint_parsed_when_present() {
        ServerToolConfig cfg = loader.validateYaml("jira", """
                type: mcp_server
                description: jira
                parameters:
                  transport: stdio
                  command: [noop]
                promptHint: |
                  cloudId is auto-injected — don't set it yourself.
                """);
        assertThat(cfg.promptHint())
                .contains("cloudId is auto-injected");
    }

    @Test
    void promptHint_defaults_to_empty_when_absent() {
        ServerToolConfig cfg = loader.validateYaml("plain", """
                type: doc_lookup
                description: plain
                parameters:
                  path: x.md
                """);
        assertThat(cfg.promptHint()).isEmpty();
    }
}
