package de.mhus.vance.brain.guard.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.script.GuardScriptHost;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit coverage of the {@code exec-check} guard handler's command/result scan. */
class ExecCheckHandlerTest {

    private final List<String> prompts = new ArrayList<>();

    private GuardContext ctx(String output) {
        return ctx(output, Map.of());
    }

    private GuardContext ctx(String output, Map<String, Object> params) {
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .build();
        GuardScriptHost host = new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                prompts.add(prompt);
                return true;
            }

            @Override
            public boolean deny(String reason) {
                throw new AssertionError("deny is not available at the stop point");
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                throw new AssertionError("not used here");
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new AssertionError("not used here");
            }
        };
        return new GuardContext(
                process,
                GuardPoint.STOP,
                "task",
                output,
                0,
                2,
                true,
                null,
                null,
                params,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                host);
    }

    private String lastPrompt() {
        return prompts.isEmpty() ? null : prompts.get(prompts.size() - 1);
    }

    @Test
    void commandMention_withoutResult_fires() {
        new ExecCheckHandler().onStop(ctx("I refactored the module and ran mvn to be sure. Done."));
        assertThat(lastPrompt()).contains("mvn").contains("report the outcome");
    }

    @Test
    void commandMention_withResult_staysQuiet() {
        new ExecCheckHandler().onStop(ctx("Ran mvn — BUILD SUCCESS in 4s."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void commandMention_withExitCode_staysQuiet() {
        new ExecCheckHandler().onStop(ctx("pytest finished, exit code 0."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void noCommandMention_staysQuiet() {
        new ExecCheckHandler().onStop(ctx("I updated the spec and the design doc. Nothing to build."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void proseWords_areNotCommands() {
        // "make" was deliberately dropped from the defaults — too common in prose.
        new ExecCheckHandler().onStop(ctx("I will make sure the login works. Done."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void customParams_overrideDefaults() {
        Map<String, Object> params = Map.of(
                "commands", List.of("\\bdeploy-prod\\b"),
                "results", List.of("\\bdeployed\\b"));
        new ExecCheckHandler().onStop(ctx("Time to deploy-prod.", params));
        assertThat(lastPrompt()).contains("deploy-prod");

        // The result marker from the custom set silences it.
        new ExecCheckHandler().onStop(ctx("deploy-prod completed — deployed to stage.", params));
        assertThat(prompts).hasSize(1);
    }
}
