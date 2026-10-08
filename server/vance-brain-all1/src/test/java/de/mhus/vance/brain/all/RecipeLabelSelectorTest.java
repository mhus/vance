package de.mhus.vance.brain.all;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every {@code "@label"} selector in a bundled recipe matches at least one
 * built-in tool. An unknown selector silently expands to nothing — a typo
 * ({@code @side_effect}) or a label renamed on the tools would quietly stop a
 * recipe from removing or deferring what it means to, and nothing else would
 * notice.
 *
 * <p>Two labels exist only at runtime and are exempt: {@code browser} is
 * carried by client-registered foot packs, {@code mail} by the SMTP/IMAP
 * tools a factory builds from operator configuration.
 */
class RecipeLabelSelectorTest {

    private static final Set<String> RUNTIME_ONLY = Set.of("browser", "mail");

    /** A YAML list item that is a quoted label selector: {@code - "@write"}. */
    private static final Pattern SELECTOR = Pattern.compile("(?m)^\\s*-\\s*[\"']@([a-z][a-z0-9-]*)[\"']");

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
        for (Resource recipe : recipes) {
            String yaml = recipe.getContentAsString(StandardCharsets.UTF_8);
            Matcher m = SELECTOR.matcher(yaml);
            while (m.find()) selectors.add(m.group(1));
        }
        assertThat(selectors).isNotEmpty();

        Set<String> unknown = new TreeSet<>(selectors);
        unknown.removeAll(known);
        unknown.removeAll(RUNTIME_ONLY);
        assertThat(unknown).as("recipe selectors no built-in tool carries").isEmpty();
    }
}
