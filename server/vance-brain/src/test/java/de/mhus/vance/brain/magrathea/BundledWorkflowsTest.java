package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The bundled workflow examples parse — start state present, every
 * {@code on:}/{@code catch:} target a declared state. They ship as defaults
 * and get copied; a broken one fails every run that uses it, silently, at
 * spawn time.
 */
class BundledWorkflowsTest {

    private static final String PATTERN = "classpath*:vance-defaults/_vance/workflows/*.yaml";

    @Test
    void everyBundledWorkflowParses() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        assertThat(resources).as("bundled workflows on the classpath").isNotEmpty();
        for (Resource r : resources) {
            String name = r.getFilename().replaceFirst("\\.yaml$", "");
            ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml(name, read(r));
            assertThat(wf.states()).as("%s has states", name).isNotEmpty();
        }
    }

    /**
     * A phase agent that asks back hands the person a question, not a plan:
     * {@code needs_input} goes to a FEEDBACK gate whose answer loops back
     * into the same phase — never into the approval gate.
     */
    @Test
    void waterfall_routesNeedsInputToAQuestionGate_notToTheApproval() throws IOException {
        Resource r = new PathMatchingResourcePatternResolver()
                .getResource("classpath:vance-defaults/_vance/workflows/waterfall.yaml");
        ResolvedMagratheaWorkflow wf = MagratheaWorkflowLoader.parseYaml("waterfall", read(r));

        assertThat(wf.states().get("planning").onOutcomes()).containsEntry("needs_input", "ask_planning");
        assertThat(wf.states().get("ask_planning").onOutcomes()).containsEntry("success", "planning");
        assertThat(wf.states().get("ask_planning").storeAs()).isEqualTo("planning_answer");

        assertThat(wf.states().get("implementation").onOutcomes()).containsEntry("needs_input", "ask_implementation");
        assertThat(wf.states().get("ask_implementation").onOutcomes()).containsEntry("success", "implementation");

        assertThat(wf.states().get("review").onOutcomes()).containsEntry("needs_input", "accept");
    }

    private static String read(Resource r) throws IOException {
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
