package de.mhus.vance.brain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Structural check over <b>every</b> bundled template definition in the tree —
 * this module's and every addon's.
 *
 * <p>Why it reads the source tree instead of the classpath: the addon templates
 * live in jars that {@code vance-brain} does not depend on (the dependency runs
 * the other way), so no module's test classpath sees all of them. Only the dev
 * bundles do, and they carry no test dependencies. Reading the sibling modules'
 * resources is the same trick the script harness uses for {@code qa/kits}.
 *
 * <p>What it buys: a definition that violates the loader's rules is not a
 * compile error and not a test failure anywhere else — it is a WARN at boot and
 * a template that has quietly vanished from the create dialog. The most likely
 * mistake is writing {@code name:} next to {@code app:} (the old shape of every
 * app template, and what a copy-paste from an older definition produces).
 * {@code BundledTemplateStructureTest} renders two bodies end to end; this one
 * covers all definitions shallowly. Both are needed — neither replaces the
 * other.
 */
class BundledTemplateDefinitionsTest {

    private static final String TEMPLATE_DIR =
            "src/main/resources/vance-defaults/_vance/templates";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void everyDefinition_obeysTheLoaderRules() {
        List<Path> definitions = definitions();

        for (Path def : definitions) {
            String where = label(def);
            Map<String, Object> spec = parse(def);

            assertThat(spec).as("%s: definition must be a map with title+description", where)
                    .containsKeys("title", "description");

            Path body = bodyOf(def);
            String app = spec.get("app") instanceof String s ? s : null;

            if (app != null) {
                // The application owns filename and MIME — TemplateLoader
                // refuses both, which means the template disappears.
                assertThat(spec).as("%s declares app='%s' and must not declare name/type", where, app)
                        .doesNotContainKeys("name", "type");
                // A body next to app: is ignored at runtime, so it is dead
                // weight in the repo rather than a failure — but dead weight
                // that reads like the source of truth.
                assertThat(body).as("%s declares app='%s'; its body file is ignored and should be deleted",
                        where, app).isNull();
            } else {
                assertThat(body).as("%s has no app: and needs a body file", where).isNotNull();
                if (spec.get("name") instanceof Map<?, ?> name
                        && "fixed".equals(name.get("mode"))) {
                    assertThat(name.get("value"))
                            .as("%s: name.mode=fixed requires name.value", where)
                            .isNotNull();
                }
                String bodyText = read(body);
                assertThatCode(() -> renderer.compile(bodyText))
                        .as("%s: body must be a valid Pebble template", where)
                        .doesNotThrowAnyException();
            }

            assertThatCode(() -> FormFieldYamlParser.parseFields(spec.get("fields"), "fields"))
                    .as("%s: fields must parse", where)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void theScanActuallyFindsTheTemplates() {
        // Without this, a wrong path or a moved resource directory turns the
        // test above into a loop over nothing that passes forever.
        List<String> names = definitions().stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();

        assertThat(names).hasSizeGreaterThanOrEqualTo(25);
        assertThat(names).contains("workflow.yaml", "meeting-notes.yaml", "kanban.yaml",
                "workbook.yaml", "search.yaml");
    }

    @Test
    void everyApplicationTemplate_isAppRouted() {
        // The inverse of the rule above: an app template that still writes its
        // own manifest is exactly what the app: routing removed, and a new one
        // would reintroduce a second manifest schema to keep in sync.
        for (Path def : definitions()) {
            Map<String, Object> spec = parse(def);
            Object tags = spec.get("tags");
            boolean isApp = tags instanceof List<?> list && list.contains("app");
            if (!isApp) continue;
            assertThat(spec.get("app"))
                    .as("%s is tagged `app` and must declare app: (see document-templates §2a)",
                            def.getFileName())
                    .isNotNull();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Every {@code <name>.yaml} under any module's bundled template directory.
     * Surefire runs with the module basedir as working directory, so {@code ..}
     * is the reactor root next to the addons.
     */
    private static List<Path> definitions() {
        Path server = Path.of("..").toAbsolutePath().normalize();
        assertThat(server.resolve("vance-brain")).as("reactor root not found from %s", server)
                .exists();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(server)) {
            modules.map(m -> m.resolve(TEMPLATE_DIR))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(dir -> {
                        try (Stream<Path> files = Files.list(dir)) {
                            files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                                    .filter(p -> !p.getFileName().toString().contains(".tmpl."))
                                    .forEach(out::add);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /** {@code <module>/<file>} — the module is what the reader has to go and open. */
    private static String label(Path definition) {
        Path server = Path.of("..").toAbsolutePath().normalize();
        return server.relativize(definition).getName(0) + "/" + definition.getFileName();
    }

    /** The paired body file, or {@code null} when the template has none. */
    private static Path bodyOf(Path definition) {
        String filename = definition.getFileName().toString();
        String prefix = filename.substring(0, filename.length() - ".yaml".length()) + ".tmpl.";
        try (Stream<Path> siblings = Files.list(definition.getParent())) {
            return siblings
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path definition) {
        Object parsed = new Yaml().load(read(definition));
        assertThat(parsed).as("%s must parse as YAML", definition.getFileName())
                .isInstanceOf(Map.class);
        return (Map<String, Object>) parsed;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
