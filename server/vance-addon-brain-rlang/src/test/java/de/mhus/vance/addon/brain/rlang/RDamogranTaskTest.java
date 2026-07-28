package de.mhus.vance.addon.brain.rlang;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.damogran.DamogranContext;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranTaskResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guard branches of {@link RDamogranTask} that resolve before any Rserve
 * roundtrip. The eval path needs a live daemon and is opt-in integration
 * territory.
 */
class RDamogranTaskTest {

    private final RDamogranTask task = new RDamogranTask(null, null, null);

    @Test
    void type_isR() {
        assertThat(task.type()).isEqualTo("r");
    }

    @Test
    void execute_clientTarget_noWorkspace_failsWithWorkOnlyMessage() {
        DamogranContext ctx = new DamogranContext(
                "acme", "proj", null, "ws", "ws-1",
                /* workspacePath */ null, "CLIENT", null, null);

        DamogranTaskResult result = task.execute(ctx, spec(Map.of("code", "1 + 1")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("WORK target");
    }

    @Test
    void execute_workspacePresent_noScript_failsWithMissingScriptMessage() {
        DamogranContext ctx = new DamogranContext(
                "acme", "proj", null, "ws", "ws-1",
                Path.of("/tmp/does-not-matter"), "WORK", null, null);

        DamogranTaskResult result = task.execute(ctx, spec(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("'code'").contains("'script'");
    }

    private static TaskSpec spec(Map<String, Object> params) {
        return new TaskSpec("r", params, List.of());
    }
}
