package de.mhus.vance.shared.ursaevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.ursaevents.EventSource;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic test for {@link UrsaEventLoader#validateYaml}. Bypasses
 * {@code DocumentService} via the public validate entry point.
 */
class UrsaEventLoaderTest {

    private final UrsaEventLoader loader = new UrsaEventLoader(null);

    @Test
    void parses_minimal_event_with_only_workflow() {
        ResolvedUrsaEvent e = loader.validateYaml("ping", """
                workflow: ping-workflow
                auth:
                  public: true
                """);

        assertThat(e.name()).isEqualTo("ping");
        assertThat(e.workflow()).isEqualTo("ping-workflow");
        assertThat(e.enabled()).isTrue();
        assertThat(e.requiresAuth()).isFalse();
        assertThat(e.methods()).isEmpty();
        assertThat(e.acceptsMethod("GET")).isTrue();
        assertThat(e.acceptsMethod("POST")).isTrue();
        assertThat(e.source()).isEqualTo(EventSource.PROJECT);
    }

    @Test
    void parses_full_event_with_inline_token_and_methods() {
        ResolvedUrsaEvent e = loader.validateYaml("deploy", """
                description: trigger a deploy
                workflow: deploy-workflow
                enabled: true
                methods:
                  - POST
                auth:
                  token: secret-token-123
                params:
                  branch: main
                runAs: ci-bot
                tags:
                  - ci
                  - deploy
                """);

        assertThat(e.workflow()).isEqualTo("deploy-workflow");
        assertThat(e.description()).isEqualTo("trigger a deploy");
        assertThat(e.methods()).containsExactly("POST");
        assertThat(e.acceptsMethod("POST")).isTrue();
        assertThat(e.acceptsMethod("GET")).isFalse();
        assertThat(e.requiresAuth()).isTrue();
        assertThat(e.tokenLiteral()).isEqualTo("secret-token-123");
        assertThat(e.tokenSettingKey()).isNull();
        assertThat(e.params()).containsEntry("branch", "main");
        assertThat(e.runAs()).isEqualTo("ci-bot");
        assertThat(e.tags()).containsExactly("ci", "deploy");
    }

    @Test
    void parses_setting_based_auth() {
        ResolvedUrsaEvent e = loader.validateYaml("hook", """
                workflow: hook-workflow
                auth:
                  tokenSetting: events.deploy.token
                """);

        assertThat(e.requiresAuth()).isTrue();
        assertThat(e.tokenSettingKey()).isEqualTo("events.deploy.token");
        assertThat(e.tokenLiteral()).isNull();
    }

    @Test
    void methods_lowercase_are_normalised_to_upper() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                methods: [get, post]
                auth:
                  public: true
                """);
        // Order isn't asserted: ResolvedUrsaEvent stores methods as a Set
        // (no order guarantee from {@code Set.copyOf}).
        assertThat(e.methods()).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void enabled_false_is_respected() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                enabled: false
                auth:
                  public: true
                """);
        assertThat(e.enabled()).isFalse();
    }

    @Test
    void rejects_missing_workflow() {
        assertThatThrownBy(() -> loader.validateYaml("x",
                "description: nope\nauth:\n  public: true\n"))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("workflow");
    }

    @Test
    void rejects_both_token_and_token_setting() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                auth:
                  token: abc
                  tokenSetting: def
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void rejects_unsupported_method() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                methods: [PUT]
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("PUT");
    }

    @Test
    void rejects_empty_yaml() {
        assertThatThrownBy(() -> loader.validateYaml("x", ""))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_non_map_top_level() {
        assertThatThrownBy(() -> loader.validateYaml("x", "- one\n- two\n"))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("top-level map");
    }

    // ─── auth is mandatory ───

    @Test
    void rejects_event_without_an_auth_block() {
        // The unsafe case must be the stated one. Omitting auth used to
        // mean "public", which made it the silent one.
        assertThatThrownBy(() -> loader.validateYaml("x", "workflow: w\n"))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("auth.public: true");
    }

    @Test
    void rejects_auth_block_that_declares_nothing() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                auth:
                  comment: none of the three
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void public_true_declares_an_open_event() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                auth:
                  public: true
                """);

        assertThat(e.authPublic()).isTrue();
        assertThat(e.requiresAuth()).isFalse();
    }

    @Test
    void rejects_public_combined_with_a_token() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                auth:
                  public: true
                  token: abc
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void public_false_is_not_a_declaration() {
        // Writing `public: false` says nothing about how the event is
        // protected — it must still name a token variant.
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                auth:
                  public: false
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("exactly one");
    }

    // ─── async ───

    @Test
    void script_events_default_to_synchronous() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                script:
                  source: document
                  path: _vance/scripts/x.js
                auth:
                  public: true
                """);

        assertThat(e.resolvedAsync()).isFalse();
    }

    @Test
    void spawn_events_default_to_asynchronous() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                auth:
                  public: true
                """);

        assertThat(e.resolvedAsync()).isTrue();
    }

    @Test
    void script_event_can_opt_into_async() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                script:
                  source: document
                  path: _vance/scripts/x.js
                async: true
                auth:
                  public: true
                """);

        assertThat(e.resolvedAsync()).isTrue();
    }

    @Test
    void rejects_async_false_on_a_spawn() {
        // Waiting for a think-process is unbounded — it may block on user
        // input, which is neither a result nor an error.
        assertThatThrownBy(() -> loader.validateYaml("x", """
                recipe: r
                async: false
                auth:
                  public: true
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("only supported for 'script:'");
    }

    @Test
    void rejects_non_boolean_async() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                async: "yes"
                auth:
                  public: true
                """))
                .isInstanceOf(UrsaEventParseException.class)
                .hasMessageContaining("must be a boolean");
    }

    // ─── outputToAgents ───

    @Test
    void output_is_visible_to_agents_when_no_identity_is_crossed() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                auth:
                  public: true
                """);

        assertThat(e.runAs()).isNull();
        assertThat(e.outputVisibleToAgents()).isTrue();
    }

    @Test
    void output_is_withheld_by_default_when_runAs_crosses_identity() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                runAs: ci-bot
                auth:
                  public: true
                """);

        assertThat(e.outputVisibleToAgents()).isFalse();
    }

    @Test
    void runAs_event_can_opt_into_agent_visible_output() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                runAs: ci-bot
                outputToAgents: true
                auth:
                  public: true
                """);

        assertThat(e.outputVisibleToAgents()).isTrue();
    }

    @Test
    void output_can_be_withheld_even_without_runAs() {
        ResolvedUrsaEvent e = loader.validateYaml("x", """
                workflow: w
                outputToAgents: false
                auth:
                  public: true
                """);

        assertThat(e.outputVisibleToAgents()).isFalse();
    }
}
