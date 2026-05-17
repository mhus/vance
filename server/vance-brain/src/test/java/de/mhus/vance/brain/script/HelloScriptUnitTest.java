package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the {@code hello-script-kit} fixture's
 * {@code greet.js}. Drives the script through {@link ScriptHarness}
 * — no Brain bootstrap, no LLM, no Mongo — so a JS edit + test cycle
 * lands in under a second.
 *
 * <p>Companion to {@code HelloScriptSkillE2ETest} in the qa/ai-test
 * module: that one proves the LLM picks the tool end-to-end, this one
 * proves the script itself produces the expected return shape and
 * marker for any given input.
 *
 * <p>Surefire CWD inside vance-brain is the module directory, so the
 * relative path climbs four levels to reach {@code qa/kits/} under
 * the workbench root.
 */
class HelloScriptUnitTest {

    private static final Path GREET_JS = Path.of(
            "../../../../qa/kits/hello-script-kit/documents/skills/"
                    + "hello-script/scripts/greet.js");

    @Test
    void greet_returns_marker_with_uppercased_name() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(GREET_JS)
                .args(Map.of("name", "Klaus"))
                .build();

        ScriptResult result = harness.run();

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertThat(value)
                .containsEntry("message", "Hallo Klaus — vance hier.")
                .containsEntry("source", "hello-script-kit")
                .containsEntry("marker", "HELLO-SCRIPT-XF7341-KLAUS-Q3K9");
    }

    @Test
    void greet_logs_via_vanceLog_info() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(GREET_JS)
                .args(Map.of("name", "Ada"))
                .build();

        harness.run();

        assertThat(harness.logRecords())
                .as("greet.js must call vance.log.info exactly once with the name")
                .hasSize(1);
        assertThat(harness.logRecords().get(0).getFormattedMessage())
                .contains("hello-script greet invoked")
                .contains("Ada");
    }

    @Test
    void greet_handles_missing_name_with_default() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(GREET_JS)
                .args(Map.of()) // no name
                .build();

        ScriptResult result = harness.run();

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        // Falls back to "Welt" per the (args && args.name) guard.
        assertThat(value).containsEntry("message", "Hallo Welt — vance hier.");
        assertThat(value).containsEntry("marker", "HELLO-SCRIPT-XF7341-WELT-Q3K9");
    }

    @Test
    void greet_makes_no_tool_calls() throws Exception {
        // greet.js is a pure compute — it must not reach into the tool
        // dispatcher. Sanity-check via the harness's recorder.
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(GREET_JS)
                .args(Map.of("name", "Ford"))
                .build();

        harness.run();

        assertThat(harness.toolCalls())
                .as("greet.js is pure compute — no vance.tools.call expected")
                .isEmpty();
    }
}
