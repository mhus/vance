package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runner is a fact about the project, the workspace mode one about the pod: the
 * engine prompts read both, and the runner text must follow {@code runnerAvailable}
 * alone — present with a runner, absent without, with a workspace or without.
 */
class RunnerPromptTest {

    private static final String ARTHUR = "arthur-prompt.md";

    private static String prompt(String name) throws IOException {
        try (var in = RunnerPromptTest.class.getResourceAsStream("/vance-defaults/_vance/prompts/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PromptTemplateRenderer renderer(WorkspaceMode mode) {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setMode(mode);
        return new PromptTemplateRenderer(props);
    }

    @ParameterizedTest
    @ValueSource(strings = {"arthur-prompt.md", "eddie-prompt.md", "ford-prompt.md"})
    void aRunnerIsAnnounced_withItsManualHook_inALocalAndAnOffPod(String name) throws IOException {
        for (WorkspaceMode mode : WorkspaceMode.values()) {
            String rendered = renderer(mode).render(prompt(name), runnerContext(true));

            assertThat(rendered)
                    .as(name + " / " + mode)
                    .contains("work_target_set(kind=\"RUNNER\")")
                    .contains("manual_read('runner')");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"arthur-prompt.md", "eddie-prompt.md", "ford-prompt.md"})
    void noRunnerMeansNoRunnerText(String name) throws IOException {
        for (WorkspaceMode mode : WorkspaceMode.values()) {
            String rendered = renderer(mode).render(prompt(name), runnerContext(false));

            assertThat(rendered)
                    .as(name + " / " + mode)
                    .doesNotContain("RUNNER")
                    .doesNotContain("manual_read('runner')");
        }
    }

    @Test
    void withoutAWorkspace_theRunnerIsNamedAmongTheTargetsFileAndExecReach() throws IOException {
        String withRunner = renderer(WorkspaceMode.OFF).render(prompt(ARTHUR), runnerContext(true));
        String without = renderer(WorkspaceMode.OFF).render(prompt(ARTHUR), runnerContext(false));

        assertThat(withRunner).contains("this project's runner (`target: RUNNER`)");
        assertThat(without)
                .contains("No server workspace")
                .doesNotContain("RUNNER")
                .doesNotContain("project's runner");
    }

    @Test
    void withAWorkspace_theNoWorkspaceTextStaysAway_andOnlyTheRunnerParagraphShows() throws IOException {
        String rendered = renderer(WorkspaceMode.LOCAL).render(prompt(ARTHUR), runnerContext(true));

        assertThat(rendered).doesNotContain("No server workspace").contains("## Runner");
        assertThat(rendered).doesNotContain("it already is the work target");
    }

    @Test
    void theBuilderSetsTheSignalOnlyWhenARunnerExists() {
        assertThat(PromptContextBuilder.create().withRunner(true).build()).containsEntry("runnerAvailable", true);
        assertThat(PromptContextBuilder.create().withRunner(false).build()).doesNotContainKey("runnerAvailable");
    }

    private static Map<String, Object> runnerContext(boolean runner) {
        return PromptContextBuilder.create().withRunner(runner).build();
    }
}
