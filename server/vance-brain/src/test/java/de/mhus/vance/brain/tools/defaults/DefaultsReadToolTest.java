package de.mhus.vance.brain.tools.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads resolve a path from defaults_list to real bundled content,
 * miss / traversal / empty inputs fail closed. Runs against the real
 * classpath tree (same rationale as {@link DefaultsListToolTest}).
 */
class DefaultsReadToolTest {

    private static final String TENANT = "acme";
    private static final String PROCESS = "proc1";

    private DefaultsReadTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        tool = new DefaultsReadTool(new PathMatchingResourcePatternResolver());
        ctx = new ToolInvocationContext(TENANT, "project", "session", PROCESS, "road.runner");
    }

    @Test
    void read_creatorRecipe_returnsBundledYaml() {
        Map<String, Object> out = tool.invoke(Map.of("path", "_vance/recipes/creator.yaml"), ctx);

        assertThat(out.get("path")).isEqualTo("_vance/recipes/creator.yaml");
        assertThat(out.get("truncated")).isEqualTo(false);
        String content = (String) out.get("content");
        assertThat(content).contains("engine: ford");
        assertThat(content).contains("model: default:creator,default:analyze");
    }

    @Test
    void read_leadingSlashTolerated() {
        Map<String, Object> out = tool.invoke(Map.of("path", "/_vance/recipes/creator.yaml"), ctx);
        assertThat(out.get("path")).isEqualTo("_vance/recipes/creator.yaml");
    }

    @Test
    void read_unknownPath_failsNamingThePrefix() {
        assertThatThrownBy(() -> tool.invoke(Map.of("path", "_vance/recipes/does-not-exist.yaml"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No bundled default at")
                .hasMessageContaining("defaults_list");
    }

    @Test
    void read_missingPathParam_refused() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("path");
    }

    @Test
    void normalizePath_refusesTraversal() {
        assertThatThrownBy(() -> DefaultsReadTool.normalizePath("../etc/passwd"))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> DefaultsReadTool.normalizePath("a/../../b")).isInstanceOf(ToolException.class);
    }

    @Test
    void normalizePath_refusesAbsoluteAndDoubledRoot() {
        // A caller typing the full classpath root is refused rather than
        // silently reinterpreted — keeps the contract explicit.
        assertThatThrownBy(() -> DefaultsReadTool.normalizePath("vance-defaults/_vance/recipes/creator.yaml"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void normalizePath_refusesEmpty() {
        assertThatThrownBy(() -> DefaultsReadTool.normalizePath("")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> DefaultsReadTool.normalizePath("   ")).isInstanceOf(ToolException.class);
    }

    @Test
    void labels_andName() {
        assertThat(tool.labels()).containsExactly("defaults");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.name()).isEqualTo("defaults_read");
    }
}
