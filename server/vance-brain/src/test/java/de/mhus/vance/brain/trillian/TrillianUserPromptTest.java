package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.process.ProcessSteerTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guards the bundled Trillian-User prompt against two drifts that the build
 * cannot see, because a prompt is data.
 *
 * <p>The prompt spells out exact tool-call syntax on purpose — that is the
 * convention. So a wrong parameter name in it is not a typo, it is a broken
 * code path: {@code process_steer} throws on the missing required argument,
 * the answer never reaches the parked worker, and the same prompt forbids
 * spawning a replacement. The worker stays parked forever.
 */
class TrillianUserPromptTest {

    private static final Pattern STEER_CALL =
            Pattern.compile("process_steer\\(([^)]*)\\)");

    @Test
    void steerExamples_nameEveryRequiredParameterOfTheTool() {
        List<String> required = requiredParamsOf(steerTool());
        assertThat(required).as("tool contract changed — update the prompt too")
                .containsExactlyInAnyOrder("name", "content");

        List<String> calls = steerCalls(prompt());
        assertThat(calls).as("the prompt is the only place that teaches the call shape")
                .isNotEmpty();

        for (String args : calls) {
            for (String param : required) {
                assertThat(args)
                        .as("process_steer(%s) omits the required '%s' parameter", args, param)
                        .contains(param + "=");
            }
        }
    }

    @Test
    void serviceAccountName_isDerivedFromTheNatureRatherThanPinnedToVoid() {
        // One prompt serves every nature; TrillianSessionBootstrapper mints
        // `_trillian-<nature>-<instance>`. A hardcoded `void` tells an `adam`
        // loop a false name for its own account.
        String prompt = prompt();

        assertThat(prompt).doesNotContain("_trillian-void-");
        assertThat(prompt).contains("_trillian-{{ params.nature | default('void') }}-");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<String> steerCalls(String prompt) {
        List<String> calls = new ArrayList<>();
        Matcher m = STEER_CALL.matcher(prompt);
        while (m.find()) calls.add(m.group(1));
        return calls;
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredParamsOf(ProcessSteerTool tool) {
        Object required = tool.paramsSchema().get("required");
        return ((List<Object>) required).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static ProcessSteerTool steerTool() {
        // Schema-only probe: nothing is invoked, so mocks suffice.
        return new ProcessSteerTool(
                mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class),
                mock(ObjectProvider.class),
                mock(de.mhus.vance.shared.chat.ChatMessageService.class),
                mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
                mock(de.mhus.vance.brain.enginemessage.EngineMessageRouter.class));
    }

    /**
     * Reads the shipped prompt from {@code target/classes}. Not via
     * {@code ClassPathResource}: {@code src/test/resources} carries its own
     * {@code vance-defaults/_vance/prompts} for engine-fragment fixtures and
     * shadows the bundled one on the test classpath.
     */
    private static String prompt() {
        Path testClasses;
        try {
            testClasses = Path.of(TrillianUserPromptTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate target/test-classes", e);
        }
        Path file = testClasses.resolveSibling("classes")
                .resolve("vance-defaults/_vance/prompts/trillian-user-prompt.md");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
