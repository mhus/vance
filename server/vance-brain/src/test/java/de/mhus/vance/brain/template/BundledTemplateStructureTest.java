package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled document templates: rendering one must produce a valid
 * document, not just text.
 *
 * <p>A template is Pebble text, so nothing in the normal build notices when
 * an edit breaks the YAML indentation or renames a state without fixing the
 * transition that points at it. Running the real renderer and then the real
 * parser closes that gap — a user who picks a template gets a working
 * document, not a parse error on first use.
 */
class BundledTemplateStructureTest {

    private static final String WORKFLOW_BODY =
            "vance-defaults/_vance/templates/workflow.tmpl.yaml";
    private static final String MEETING_NOTES_BODY =
            "vance-defaults/_vance/templates/meeting-notes.tmpl.md";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void renderedTemplate_parsesAsAStartableWorkflow() {
        String rendered = render();

        ResolvedMagratheaWorkflow workflow =
                MagratheaWorkflowLoader.parseYaml("my-workflow", rendered);

        assertThat(workflow.startState()).isEqualTo("work");
        assertThat(workflow.states()).containsOnlyKeys("work", "review", "done", "failed");
    }

    @Test
    void renderedTemplate_carriesTheKindHeader() {
        // The header is what types the document; without it the flow view
        // and the kind validation never engage.
        assertThat(render()).contains("kind: vance-workflow");
    }

    @Test
    void renderedTemplate_substitutesTheFormValues() {
        String rendered = render();
        assertThat(rendered).contains("recipe: ford");
        assertThat(rendered).contains("Summarise the week");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    void emptyFormValues_stillProduceParsableYaml() {
        // strictVariables is off, so a missing value renders as empty. The
        // document must degrade to "incomplete" rather than "malformed".
        String rendered = renderer.renderStructured(templateBody(WORKFLOW_BODY), Map.of());
        assertThatCode(() -> MagratheaWorkflowLoader.parseYaml("x", rendered))
                .doesNotThrowAnyException();
    }

    @Test
    void meetingNotes_frontMatterSurvivesTheRender() {
        // Regression: with Pebble's newline trimming the `title: {{ topic }}`
        // line swallowed its newline and produced `title: Sprint Review---`,
        // so the created note had no closing front-matter fence and no kind.
        String rendered = renderer.renderStructured(
                templateBody(MEETING_NOTES_BODY),
                Map.of("topic", "Sprint Review", "attendees", "Ann", "date", "2026-08-13"));

        assertThat(rendered).startsWith("---\nkind: workpage\ntitle: Sprint Review\n---\n");
    }

    private String render() {
        return renderer.renderStructured(templateBody(WORKFLOW_BODY), Map.of(
                "summary", "Summarise the week",
                "recipe", "ford",
                "name", "my-workflow"));
    }

    private static String templateBody(String resource) {
        try (InputStream in = BundledTemplateStructureTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled template body missing: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
