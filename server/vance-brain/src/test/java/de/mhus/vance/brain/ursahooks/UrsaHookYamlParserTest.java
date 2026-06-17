package de.mhus.vance.brain.ursahooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import de.mhus.vance.shared.action.TriggerActionParser;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrsaHookYamlParserTest {

    private UrsaHookYamlParser parser;

    @BeforeEach
    void setup() {
        parser = new UrsaHookYamlParser(new TriggerActionParser());
    }

    @Test
    void parse_recipeHook_minimal_populatesFields() {
        String yaml = """
                description: notify
                recipe: notify-webhook
                params:
                  url: https://example.com/hook
                """;
        UrsaHookDef def = parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "hello");
        assertThat(def.name()).isEqualTo("hello");
        assertThat(def.event()).isEqualTo(UrsaHookEventName.PROCESS_COMPLETED);
        assertThat(def.actionType()).isEqualTo("recipe");
        assertThat(def.enabled()).isTrue();
        assertThat(def.description()).isEqualTo("notify");
        assertThat(def.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(def.action()).isInstanceOf(TriggerAction.Recipe.class);
        TriggerAction.Recipe r = (TriggerAction.Recipe) def.action();
        assertThat(r.recipe()).isEqualTo("notify-webhook");
        assertThat(r.params()).containsEntry("url", "https://example.com/hook");
    }

    @Test
    void parse_scriptHook_documentSource() {
        String yaml = """
                description: classify
                script:
                  source: document
                  path: scripts/triage.js
                  timeoutSeconds: 10
                params:
                  threshold: 0.7
                """;
        UrsaHookDef def = parser.parse(
                yaml, UrsaHookEventName.INBOX_ITEM_CREATED, UrsaHookSource.PROJECT, "classifier");
        assertThat(def.actionType()).isEqualTo("script");
        assertThat(def.action()).isInstanceOf(TriggerAction.Script.class);
        TriggerAction.Script s = (TriggerAction.Script) def.action();
        assertThat(s.source()).isEqualTo(ScriptSource.DOCUMENT);
        assertThat(s.path()).isEqualTo("scripts/triage.js");
        assertThat(s.timeoutSeconds()).isEqualTo(10);
    }

    @Test
    void parse_workflowHook() {
        String yaml = """
                workflow: post-process-pipeline
                params:
                  processId: "${event.process.id}"
                """;
        UrsaHookDef def = parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "kick");
        assertThat(def.actionType()).isEqualTo("workflow");
        assertThat(def.action()).isInstanceOf(TriggerAction.Workflow.class);
    }

    @Test
    void parse_legacyType_throwsWithMigrationHint() {
        String yaml = """
                type: js
                script: |
                  log.info("hi");
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "legacy"))
                .isInstanceOf(UrsaHookParseException.class)
                .hasMessageContaining("Hook schema changed")
                .hasMessageContaining("recipe:");
    }

    @Test
    void parse_legacyLlmFields_throwsWithMigrationHint() {
        String yaml = """
                prompt: classify {{ event.foo }}
                model: default:fast
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "legacy-llm"))
                .isInstanceOf(UrsaHookParseException.class)
                .hasMessageContaining("Hook schema changed")
                .hasMessageContaining("lightllm");
    }

    @Test
    void parse_noActionVariant_throws() {
        String yaml = """
                description: this lacks an action variant
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "x"))
                .isInstanceOf(UrsaHookParseException.class);
    }

    @Test
    void parse_timeout_aboveCeiling_throws() {
        String yaml = """
                recipe: notify
                timeout: 5m
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "x"))
                .isInstanceOf(UrsaHookParseException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    void parse_disabled_keepsRecordEnabledFalse() {
        String yaml = """
                recipe: notify
                enabled: false
                """;
        UrsaHookDef def = parser.parse(
                yaml, UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, "x");
        assertThat(def.enabled()).isFalse();
    }
}
