package de.mhus.vance.brain.action;

import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Boot-time scan of every {@link Tool} bean in the Spring context for
 * the {@link SpawnTool} marker annotation. The resulting name-set is
 * looked up by {@code ScriptToolsApi} when the bound
 * {@link ScopeLevel} is {@link ScopeLevel#TRIGGER_SCOPED}.
 *
 * <p>Spring already wires {@code List<Tool>} for the
 * {@code ToolDispatcher}; we piggy-back on the same injection so a new
 * tool either shows up here automatically (when it carries the
 * annotation) or it doesn't (and the
 * {@code AllSpawnToolsAnnotatedTest} catches the drift).
 */
@Component
@Slf4j
public final class SpawnToolRegistry {

    private final Set<String> spawnToolNames;

    public SpawnToolRegistry(List<Tool> tools) {
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            if (tool.getClass().isAnnotationPresent(SpawnTool.class)) {
                names.add(tool.name());
            }
        }
        this.spawnToolNames = Set.copyOf(names);
        log.debug("SpawnToolRegistry: {} spawn tool(s) found: {}",
                spawnToolNames.size(),
                spawnToolNames.stream().sorted().toList());
    }

    /** Names of every {@link SpawnTool}-annotated tool bean. Immutable. */
    public Set<String> spawnToolNames() {
        return spawnToolNames;
    }

    /** Convenience predicate. */
    public boolean isSpawnTool(String toolName) {
        return spawnToolNames.contains(toolName);
    }
}
