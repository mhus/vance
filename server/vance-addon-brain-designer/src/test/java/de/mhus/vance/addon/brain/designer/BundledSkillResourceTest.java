package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The bundled example skill must survive the same discovery a real boot
 * runs: the loader scans the bundled-skill classpath glob (one SKILL.md
 * per skill folder in every jar's vance-defaults tree) and skips anything
 * malformed with a WARN — which would silently ship a broken example.
 * This test fails loudly instead.
 */
class BundledSkillResourceTest {

    private static final String SKILL = "design-blueprint";

    @Test
    void bundledSkill_isDiscoveredByTheClasspathPattern() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:vance-defaults/_vance/skills/*/SKILL.md");

        List<String> paths = new ArrayList<>();
        for (Resource r : resources) {
            paths.add(r.getURL().getPath());
        }
        assertThat(paths).anyMatch(p -> p.contains("skills/" + SKILL + "/SKILL.md"));
    }

    @Test
    void bundledSkill_frontmatterIsWellFormedAndDisabled() throws Exception {
        String raw = read("vance-defaults/_vance/skills/" + SKILL + "/SKILL.md");

        String frontmatter = raw.substring(raw.indexOf("---\n") + 4, raw.indexOf("\n---", 4));
        Map<String, Object> meta = KindHeaderCodec.parseYamlBody(frontmatter);

        assertThat(meta.get("title")).isEqualTo("Design Blueprint");
        assertThat(meta.get("version")).isEqualTo("1.0.0");
        assertThat(meta.get("description")).asString().contains("/skill design-blueprint");
        // The example ships trigger-less — visible in /skill list but it
        // never activates on its own; explicit activation is the demo.
        // (A disabled skill would be INVISIBLE: the loader's list skips
        // enabled=false entries, which is exactly why this is not that.)
        assertThat(meta.get("enabled")).isEqualTo(true);
        assertThat(meta.get("triggers")).isNull();
        // The label the manual's "Design skills" convention keys on — a
        // skill without it is not findable as a design blueprint.
        assertThat(meta.get("tags").toString()).contains("design");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> referenceDocs = (List<Map<String, Object>>) meta.get("referenceDocs");
        // A mode-skill fires an announcement, not the mode itself: without
        // the action, a fresh activation would inject the full body as a
        // turn — and the agent would start designing unprompted.
        assertThat(meta.get("action")).asString().contains("nothing to do right now");
        assertThat(referenceDocs).hasSize(1);
        assertThat(referenceDocs.get(0))
                .containsEntry("file", "style.css")
                .containsEntry("title", "Designer Blueprint Stylesheet")
                .containsEntry("loadMode", "ON_DEMAND");
    }

    @Test
    void bundledSkill_referenceFileShipsAndIsNotEmpty() throws Exception {
        String css = read("vance-defaults/_vance/skills/" + SKILL + "/style.css");

        // The skill's whole point is a usable sample asset: it must carry
        // the two things the body promises — responsive breakpoints and
        // dark-mode support.
        assertThat(css).contains("@media (min-width:");
        assertThat(css).contains("prefers-color-scheme: dark");
    }

    private static String read(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
