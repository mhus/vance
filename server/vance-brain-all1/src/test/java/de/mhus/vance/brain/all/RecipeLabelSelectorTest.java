package de.mhus.vance.brain.all;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * Every {@code "@label"} selector in a bundled recipe's <b>tool</b> fields matches at least one
 * built-in tool. An unknown selector silently expands to nothing — a typo ({@code @side_effect})
 * or a label renamed on the tools would quietly stop a recipe from removing or deferring what it
 * means to, and nothing else would notice.
 *
 * <p>Scope: only values of {@code allowedTools*} keys (top level, profiles, modes — anywhere in
 * the YAML). Skill label selectors ({@code advertisedSkills: ["@coding"]}, skills.md §7e)
 * share the {@code @} syntax in the same files but select <b>skills</b> by tag, so a text scan
 * over whole recipes would report them as unknown tool labels.
 *
 * <p>Two labels exist only at runtime and are exempt: {@code browser} is carried by
 * client-registered foot packs, {@code mail} by the SMTP/IMAP tools a factory builds from
 * operator configuration.
 */
class RecipeLabelSelectorTest {

    private static final Set<String> RUNTIME_ONLY = Set.of("browser", "mail");

    /** A tool list entry that is a quoted or plain label selector: {@code "@write"} / {@code @write}. */
    private static final Pattern SELECTOR = Pattern.compile("@([a-z][a-z0-9-]*)");

    @Test
    void everyRecipeLabelSelector_matchesABuiltInTool() throws IOException, ClassNotFoundException {
        Set<String> known = new HashSet<>();
        for (Tool tool : BuiltInTools.all()) {
            if (tool.labels() != null) known.addAll(tool.labels());
        }

        Set<String> selectors = new TreeSet<>();
        Resource[] recipes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:vance-defaults/**/recipes/**/*.yaml");
        assertThat(recipes).isNotEmpty();
        Yaml yaml = new Yaml();
        for (Resource recipe : recipes) {
            Object spec = yaml.load(recipe.getContentAsString(StandardCharsets.UTF_8));
            collectToolSelectors(spec, selectors);
        }
        assertThat(selectors).isNotEmpty();

        Set<String> unknown = new TreeSet<>(selectors);
        unknown.removeAll(known);
        unknown.removeAll(RUNTIME_ONLY);
        assertThat(unknown).as("recipe tool selectors no built-in tool carries").isEmpty();
    }

    /** Walks the parsed YAML and collects label selectors from values of {@code allowedTools*} keys. */
    private static void collectToolSelectors(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && key.startsWith("allowedTools")) {
                    collectSelectorStrings(entry.getValue(), out);
                } else {
                    collectToolSelectors(entry.getValue(), out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectToolSelectors(item, out);
            }
        }
    }

    private static void collectSelectorStrings(Object node, Set<String> out) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectSelectorStrings(item, out);
            }
        } else if (node instanceof String value) {
            Matcher m = SELECTOR.matcher(value.strip());
            if (m.matches()) out.add(m.group(1));
        }
    }
}
