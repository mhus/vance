package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.api.hooks.HookType;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HookYamlParserTest {

    private HookYamlParser parser;

    @BeforeEach
    void setup() {
        parser = new HookYamlParser(new PromptTemplateRenderer());
    }

    @Test
    void parse_jsHook_minimal_populatesFields() {
        String yaml = """
                type: js
                description: hello
                script: |
                  log.info("ok");
                """;
        HookDef def = parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "hello");
        assertThat(def.name()).isEqualTo("hello");
        assertThat(def.event()).isEqualTo(HookEventName.PROCESS_COMPLETED);
        assertThat(def.type()).isEqualTo(HookType.JS);
        assertThat(def.enabled()).isTrue();
        assertThat(def.description()).isEqualTo("hello");
        assertThat(def.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(def.script()).contains("log.info");
        assertThat(def.prompt()).isNull();
    }

    @Test
    void parse_llmHook_minimal_populatesFields() {
        String yaml = """
                type: llm
                description: classify
                model: "default:fast"
                maxTokens: 256
                timeout: 10s
                prompt: |
                  Classify {{ event.foo }}.
                """;
        HookDef def = parser.parse(
                yaml, HookEventName.INSIGHT_SAVED, HookSource.PROJECT, "classifier");
        assertThat(def.type()).isEqualTo(HookType.LLM);
        assertThat(def.model()).isEqualTo("default:fast");
        assertThat(def.maxTokens()).isEqualTo(256);
        assertThat(def.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(def.prompt()).contains("{{ event.foo }}");
    }

    @Test
    void parse_jsHook_withPrompt_isRejected() {
        String yaml = """
                type: js
                script: noop;
                prompt: "this does not belong here"
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "bad"))
                .isInstanceOf(HookParseException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void parse_llmHook_missingModel_throws() {
        String yaml = """
                type: llm
                prompt: do it
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "x"))
                .isInstanceOf(HookParseException.class)
                .hasMessageContaining("model");
    }

    @Test
    void parse_timeout_aboveCeiling_throws() {
        String yaml = """
                type: js
                timeout: 5m
                script: noop;
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "x"))
                .isInstanceOf(HookParseException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    void parse_unknownType_throws() {
        String yaml = """
                type: bash
                script: ls
                """;
        assertThatThrownBy(() -> parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "x"))
                .isInstanceOf(HookParseException.class)
                .hasMessageContaining("type");
    }

    @Test
    void parse_disabled_keepsRecordEnabledFalse() {
        String yaml = """
                type: js
                enabled: false
                script: noop;
                """;
        HookDef def = parser.parse(
                yaml, HookEventName.PROCESS_COMPLETED, HookSource.PROJECT, "x");
        assertThat(def.enabled()).isFalse();
    }
}
