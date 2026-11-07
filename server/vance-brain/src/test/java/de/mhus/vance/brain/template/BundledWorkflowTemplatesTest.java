package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards every bundled template tagged {@code workflow}: rendered with its own
 * form defaults it must parse as a startable Magrathea workflow.
 *
 * <p>The templates are added as plain files, so nothing else notices a broken
 * YAML indentation, a renamed state whose transition was not fixed, or a form
 * field whose default no longer renders into a valid plan. The test discovers
 * the templates itself, so a new workflow template is covered by dropping the
 * two files in, without touching this class.
 */
class BundledWorkflowTemplatesTest {

    private static final String TEMPLATES = "classpath:vance-defaults/_vance/templates/";
    private static final String WORKFLOW_TAG = "workflow";
    private static final String WORKFLOW_FOLDER = "_vance/workflows";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void thereAreBundledWorkflowTemplates() {
        assertThat(workflowTemplates()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void everyWorkflowTemplate_rendersWithItsDefaultsIntoAStartableWorkflow() {
        for (Template t : workflowTemplates()) {
            String rendered = renderer.renderStructured(t.body(), t.defaultContext());

            // A secret reference ({{secret:user:oauth.slack.access_token}}) is meant to stay: the tool call resolves it
            // at run time. Anything else in double braces is a Pebble expression that was not rendered.
            assertThat(rendered.replaceAll("\\{\\{secret:[^}]*}}", ""))
                    .as("%s: unresolved placeholder", t.name())
                    .doesNotContain("{{");
            assertThat(rendered).as("%s: kind header", t.name()).contains("kind: vance-workflow");

            ResolvedMagratheaWorkflow workflow = MagratheaWorkflowLoader.parseYaml(t.name(), rendered);
            assertThat(workflow.states()).as("%s: states", t.name()).isNotEmpty();
            assertThat(workflow.states()).as("%s: start state exists", t.name()).containsKey(workflow.startState());
        }
    }

    @Test
    void everyWorkflowTemplate_landsWhereNameBasedTriggersLook() {
        for (Template t : workflowTemplates()) {
            Object folder = t.definition().get("folder");
            if (folder != null) {
                assertThat(folder).as("%s: folder", t.name()).isEqualTo(WORKFLOW_FOLDER);
            }
        }
    }

    @Test
    void theNamedWorkflowTemplates_pinTheirFolder() {
        for (Template t : workflowTemplates()) {
            if (!t.name().startsWith("workflow-")) continue;
            assertThat(t.definition().get("folder")).as("%s: folder", t.name()).isEqualTo(WORKFLOW_FOLDER);
        }
    }

    // ──────────────────── Discovery ────────────────────

    private List<Template> workflowTemplates() {
        List<Template> out = new ArrayList<>();
        try {
            Resource[] defs = new PathMatchingResourcePatternResolver().getResources(TEMPLATES + "*.yaml");
            for (Resource def : defs) {
                String file = def.getFilename();
                if (file == null || file.contains(".tmpl.")) continue;
                Map<String, Object> definition = yaml(read(def));
                if (!tags(definition).contains(WORKFLOW_TAG)) continue;
                String name = file.substring(0, file.length() - ".yaml".length());
                Resource body = new PathMatchingResourcePatternResolver().getResource(TEMPLATES + name + ".tmpl.yaml");
                out.add(new Template(name, definition, read(body)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list bundled templates", e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        Object loaded = new Yaml().load(text);
        return loaded instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Map<String, Object> definition) {
        Object raw = definition.get("tags");
        return raw instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    private record Template(String name, Map<String, Object> definition, String body) {

        /** What the form would submit if the user accepted every default. */
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultContext() {
            Map<String, Object> ctx = new LinkedHashMap<>();
            Object fields = definition.get("fields");
            if (fields instanceof List<?> list) {
                for (Object f : list) {
                    Map<String, Object> field = (Map<String, Object>) f;
                    ctx.put(String.valueOf(field.get("name")), sampleValue(field));
                }
            }
            ctx.put("name", name);
            ctx.put("date", "2026-11-04");
            ctx.put("user", "tester");
            ctx.put("project", "demo");
            ctx.put("lang", "en");
            return ctx;
        }

        @SuppressWarnings("unchecked")
        private static Object sampleValue(Map<String, Object> field) {
            Object dflt = field.get("defaultValue");
            if (dflt != null) return dflt;
            if ("select".equals(field.get("type")) && field.get("choices") instanceof List<?> c && !c.isEmpty()) {
                return ((Map<String, Object>) c.get(0)).get("value");
            }
            return "Sample text";
        }
    }
}
