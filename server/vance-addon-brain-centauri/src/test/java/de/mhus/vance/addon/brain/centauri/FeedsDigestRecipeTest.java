package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the bundled `feeds-digest` recipe.
 *
 * <p>A recipe is resolved lazily by name, so a mistake in it surfaces at the
 * first scheduled run — at 07:30, in somebody's inbox, as a job that did
 * nothing. Two failure modes are worth a test:
 *
 * <ul>
 *   <li>A top-level field indented under {@code params}. That map is open by
 *       design (unknown keys become engine parameters), so YAML parses, the
 *       recipe loads, and the field simply never existed. This is the failure
 *       {@code BundledRecipeStructureTest} was written for on the brain side —
 *       the addon's recipes are not on that sweep's classpath.
 *   <li>A missing tool in {@code allowedToolsAdd}. {@code feed_read} and
 *       {@code feed_sources} are {@code deferred()}: off every engine's default
 *       surface. A scheduled worker that has to discover its one tool first
 *       wastes a round-trip at best and reports "I have no feed tool" at worst.
 * </ul>
 */
class FeedsDigestRecipeTest {

    /** Fields the recipe loader reads from the top level, mirroring the brain sweep. */
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "engine", "description", "title", "promptPrefix", "promptMode",
            "dataRelayCorrection", "allowedToolsAdd", "allowedToolsRemove",
            "allowedToolsKeep", "allowedToolsDefer", "allowedToolsDropFirst",
            "defaultActiveSkills", "allowedSkills", "triggers", "tags",
            "modes", "profiles", "guard", "internal", "listed", "locked");

    @Test
    void digestRecipe_declaresAnEngineAndAPrompt() {
        Map<String, Object> spec = digest();

        assertThat(spec).containsKey("engine").containsKey("promptPrefix");
        assertThat(String.valueOf(spec.get("promptPrefix"))).contains("feed_read");
    }

    @Test
    void digestRecipe_surfacesTheDeferredFeedTools() {
        Object tools = digest().get("allowedToolsAdd");

        assertThat(tools).isInstanceOf(List.class);
        List<String> names = ((List<?>) tools).stream().map(String::valueOf).toList();
        assertThat(names)
                .as("feed_read/feed_sources are deferred(); a scheduled worker cannot "
                        + "discover them first, and inbox_post is how the digest is delivered")
                .contains("feed_read", "feed_sources", "inbox_post");
    }

    @Test
    void digestRecipe_namesTheInboxRecipientInItsExampleCall() {
        // `targetUserId` is a required parameter of `inbox_post` with no
        // fallback to the runAs user. The prompt spells out the exact call
        // shape on purpose, so leaving the parameter out of it means the one
        // productive call of a scheduled job throws — or the model invents a
        // user id and the digest lands with a stranger. `whoami` is the
        // sanctioned source for the id.
        String prompt = String.valueOf(digest().get("promptPrefix"));

        assertThat(prompt).contains("targetUserId");
        assertThat(prompt).contains("whoami");
    }

    @Test
    void digestRecipe_isNotOfferedInTheUserPicker() {
        // It is a scheduled worker, not a chat mode — somebody who wants to read
        // a feed opens the feed.
        assertThat(digest().get("listed")).isEqualTo(false);
    }

    @Test
    void noAddonRecipeHidesATopLevelFieldInsideParams() {
        for (Path recipe : bundledRecipes()) {
            Map<String, Object> spec = parse(recipe);
            if (!(spec.get("params") instanceof Map<?, ?> params)) {
                continue;
            }
            for (Object key : params.keySet()) {
                assertThat(TOP_LEVEL_FIELDS)
                        .as("%s → params.%s is accepted silently and has no effect — "
                                + "move it to the top level", recipe.getFileName(), key)
                        .doesNotContain(String.valueOf(key));
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> digest() {
        Path hit = bundledRecipes().stream()
                .filter(p -> p.getFileName().toString().equals("feeds-digest.yaml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("feeds-digest.yaml is not bundled"));
        return parse(hit);
    }

    private static List<Path> bundledRecipes() {
        try {
            Path dir = new ClassPathResource("vance-defaults/_vance/recipes")
                    .getFile().toPath();
            try (var files = Files.list(dir)) {
                List<Path> yaml = files
                        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted()
                        .toList();
                assertThat(yaml).as("bundled addon recipes found").isNotEmpty();
                return yaml;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path recipe) {
        try {
            return (Map<String, Object>) new Yaml()
                    .load(Files.readString(recipe, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
