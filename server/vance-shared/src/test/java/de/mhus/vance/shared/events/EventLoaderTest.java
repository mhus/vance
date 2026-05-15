package de.mhus.vance.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.events.EventSource;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic test for {@link EventLoader#validateYaml}. Bypasses
 * {@code DocumentService} via the public validate entry point.
 */
class EventLoaderTest {

    private final EventLoader loader = new EventLoader(null);

    @Test
    void parses_minimal_event_with_only_workflow() {
        ResolvedEvent e = loader.validateYaml("ping", """
                workflow: ping-workflow
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
        ResolvedEvent e = loader.validateYaml("deploy", """
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
        ResolvedEvent e = loader.validateYaml("hook", """
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
        ResolvedEvent e = loader.validateYaml("x", """
                workflow: w
                methods: [get, post]
                """);
        // Order isn't asserted: ResolvedEvent stores methods as a Set
        // (no order guarantee from {@code Set.copyOf}).
        assertThat(e.methods()).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void enabled_false_is_respected() {
        ResolvedEvent e = loader.validateYaml("x", """
                workflow: w
                enabled: false
                """);
        assertThat(e.enabled()).isFalse();
    }

    @Test
    void rejects_missing_workflow() {
        assertThatThrownBy(() -> loader.validateYaml("x", "description: nope\n"))
                .isInstanceOf(EventParseException.class)
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
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void rejects_unsupported_method() {
        assertThatThrownBy(() -> loader.validateYaml("x", """
                workflow: w
                methods: [PUT]
                """))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("PUT");
    }

    @Test
    void rejects_empty_yaml() {
        assertThatThrownBy(() -> loader.validateYaml("x", ""))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_non_map_top_level() {
        assertThatThrownBy(() -> loader.validateYaml("x", "- one\n- two\n"))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("top-level map");
    }
}
