package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.tools.hactar.WorkflowStartTool;
import de.mhus.vance.brain.tools.hooks.HookCreateTool;
import de.mhus.vance.brain.tools.hooks.HookDeleteTool;
import de.mhus.vance.brain.tools.hooks.HookUpdateTool;
import de.mhus.vance.brain.tools.process.ProcessCreateTool;
import de.mhus.vance.brain.tools.process.ProcessRunTool;
import de.mhus.vance.brain.tools.scheduler.SchedulerCreateTool;
import de.mhus.vance.brain.tools.script.ScriptRunDocTool;
import de.mhus.vance.brain.tools.script.ScriptRunWorkspaceTool;
import de.mhus.vance.brain.tools.scheduler.SchedulerDeleteTool;
import de.mhus.vance.brain.tools.scheduler.SchedulerUpdateTool;
import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Anti-drift guard: every tool that spawns a Process / Workflow / Hook
 * / Scheduler / Event entry must carry the {@link SpawnTool} marker
 * so the trigger-scoped sandbox (see
 * {@code planning/trigger-actions.md} §8) can refuse to run it from
 * within a trigger script.
 *
 * <p>When you add a new spawn-capable tool: register its class here so
 * this test pins the annotation on it. The list is intentionally
 * hand-maintained — if you forget to update the list AND the
 * annotation, the regression slips in silently and nobody notices.
 *
 * <p>This is not a scan of the entire classpath — it's a curated set.
 * Reflection across all {@code implements Tool} classes would either
 * pull Spring up or build a Reflections dependency, both bigger than
 * the value here.
 */
class AllSpawnToolsAnnotatedTest {

    /** Curated list — keep in sync with §8 of the plan. */
    private static final List<Class<? extends Tool>> EXPECTED_SPAWN_TOOLS = List.of(
            ProcessCreateTool.class,
            ProcessRunTool.class,
            ScriptRunDocTool.class,
            ScriptRunWorkspaceTool.class,
            WorkflowStartTool.class,
            SchedulerCreateTool.class,
            SchedulerUpdateTool.class,
            SchedulerDeleteTool.class,
            HookCreateTool.class,
            HookUpdateTool.class,
            HookDeleteTool.class);

    @Test
    void every_curated_spawn_tool_class_carries_SpawnTool_annotation() {
        for (Class<? extends Tool> cls : EXPECTED_SPAWN_TOOLS) {
            assertThat(cls.isAnnotationPresent(SpawnTool.class))
                    .as("Tool class %s must be annotated with @SpawnTool", cls.getSimpleName())
                    .isTrue();
        }
    }
}
