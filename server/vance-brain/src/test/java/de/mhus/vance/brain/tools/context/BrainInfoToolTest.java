package de.mhus.vance.brain.tools.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Output mapping of {@link BrainInfoTool}: every build fact the service
 * carries must surface under a stable key — agents key on these names
 * (the {@code vance-sources} creator manual maps {@code commit} onto
 * {@code git_checkout}).
 */
class BrainInfoToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("tenant", "project", "session", "process", "user");

    @Test
    void invoke_mapsAllBuildFacts() {
        BrainBuildInfo info = stampedInfo("0.4.0-SNAPSHOT", "2026-09-09T10:00:00Z", "abc123", "main", "true");
        BrainInfoTool tool = new BrainInfoTool(info);

        Map<String, Object> out = tool.invoke(Map.of(), CTX);

        assertThat(out)
                .containsEntry("version", "0.4.0-SNAPSHOT")
                .containsEntry("buildTime", "2026-09-09T10:00:00Z")
                .containsEntry("commit", "abc123")
                .containsEntry("branch", "main")
                .containsEntry("dirty", true);
    }

    @Test
    void invoke_unstampedBuildReportsUnknownNotGaps() {
        BrainBuildInfo info = new BrainBuildInfo("dev", "unknown", loaderReturning(new ByteArrayResource(new byte[0])));
        BrainInfoTool tool = new BrainInfoTool(info);

        Map<String, Object> out = tool.invoke(Map.of(), CTX);

        assertThat(out)
                .containsEntry("version", "dev")
                .containsEntry("commit", "unknown")
                .containsEntry("branch", "unknown")
                .containsEntry("dirty", false);
    }

    @Test
    void tool_isDeferredReadOnlyProbe() {
        BrainInfoTool tool = new BrainInfoTool(stampedInfo("0.4.0-SNAPSHOT", "t", "c", "b", "false"));

        assertThat(tool.name()).isEqualTo("brain_info");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.labels()).containsExactly("read-only");
        assertThat(tool.contributesPrak()).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    /** Production constructor with a stamped in-memory git.properties. */
    private static BrainBuildInfo stampedInfo(
            String version, String buildTime, String commit, String branch, String dirty) {
        String props = "git.commit.id=" + commit + "\ngit.branch=" + branch + "\ngit.dirty=" + dirty + "\n";
        return new BrainBuildInfo(
                version, buildTime, loaderReturning(new ByteArrayResource(props.getBytes(StandardCharsets.UTF_8))));
    }

    private static ResourceLoader loaderReturning(Resource resource) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return BrainInfoToolTest.class.getClassLoader();
            }
        };
    }
}
