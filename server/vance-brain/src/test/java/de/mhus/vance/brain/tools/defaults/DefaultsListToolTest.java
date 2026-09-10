package de.mhus.vance.brain.tools.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The tools are pure classpath reads, so the tests run against the real
 * bundled tree: listing narrows by prefix, the entries match paths the
 * read tool then resolves, and traversal is refused. No mocking — a
 * mock would only restate the implementation, and the classpath tree is
 * the thing being tested.
 */
class DefaultsListToolTest {

    private static final String TENANT = "acme";
    private static final String PROCESS = "proc1";

    private DefaultsListTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        tool = new DefaultsListTool(new PathMatchingResourcePatternResolver());
        ctx = new ToolInvocationContext(TENANT, "project", "session", PROCESS, "road.runner");
    }

    @Test
    void list_recipesFolder_returnsBundledRecipes() {
        Map<String, Object> out = tool.invoke(Map.of("path", "_vance/recipes/"), ctx);

        assertThat(out.get("prefix")).isEqualTo("_vance/recipes/");
        assertThat(out.get("root")).isEqualTo("vance-defaults/");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        assertThat(entries).isNotEmpty();

        // Every entry lives under the requested prefix and is a yaml file.
        for (Map<String, Object> entry : entries) {
            assertThat((String) entry.get("path")).startsWith("_vance/recipes/");
            assertThat((String) entry.get("path")).endsWith(".yaml");
        }
        // The creator recipe is a known bundled file.
        assertThat(entries.stream().map(e -> e.get("path"))).contains("_vance/recipes/creator.yaml");
    }

    @Test
    void list_bareFolderName_treatedAsFolderPrefix() {
        Map<String, Object> out = tool.invoke(Map.of("path", "_vance/recipes"), ctx);
        assertThat(out.get("prefix")).isEqualTo("_vance/recipes/");
    }

    @Test
    void list_leadingSlashTolerated() {
        Map<String, Object> out = tool.invoke(Map.of("path", "/_vance/recipes/"), ctx);
        assertThat(out.get("prefix")).isEqualTo("_vance/recipes/");
    }

    @Test
    void list_emptyPath_returnsEverythingUnderVanceDefaults() {
        Map<String, Object> out = tool.invoke(Map.of(), ctx);
        assertThat(out.get("prefix")).isEqualTo("");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        // The bundled tree is large — sanity-check it spans the major roots.
        assertThat(entries.stream().map(e -> e.get("path")).toList())
                .anyMatch(p -> p.toString().startsWith("_vance/recipes/"))
                .anyMatch(p -> p.toString().startsWith("_vance/model/"))
                .anyMatch(p -> p.toString().startsWith("_vance/manuals/"));
    }

    @Test
    void list_unknownPrefix_returnsEmpty() {
        Map<String, Object> out = tool.invoke(Map.of("path", "_vance/does-not-exist/"), ctx);
        assertThat(out.get("count")).isEqualTo(0);
    }

    @Test
    void labels_areDefaultsOnly_andDeferred() {
        assertThat(tool.labels()).containsExactly("defaults");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.name()).isEqualTo("defaults_list");
    }

    @Test
    void traversalPrefix_isRefusedByTheReadCompanion() {
        // The list tool itself does not accept paths with '..' (normalizePrefix
        // keeps them literal and the scan returns nothing), but the security
        // boundary is enforced by defaults_read — pin it here so the contract
        // lives next to the listing tests.
        DefaultsReadTool read = new DefaultsReadTool(new PathMatchingResourcePatternResolver());
        assertThatThrownBy(() -> read.normalizePath("../secret.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("relative to vance-defaults/");
    }
}
