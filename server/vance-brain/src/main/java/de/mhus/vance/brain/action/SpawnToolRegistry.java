package de.mhus.vance.brain.action;

import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time scan of every {@link Tool} bean in the Spring context for
 * the {@link SpawnTool} marker annotation. The resulting name-set is
 * looked up by {@code ScriptToolsApi} when the bound
 * {@link ScopeLevel} is {@link ScopeLevel#TRIGGER_SCOPED}.
 *
 * <p>Tools are pulled via {@link ObjectProvider} and the scan runs on
 * {@link ContextRefreshedEvent}. Both decisions are deliberate: eager
 * constructor injection of {@code List<Tool>} would close a circular
 * bean graph (some tools indirectly depend on {@code GraaljsScriptExecutor}
 * which depends on this registry). The lazy provider plus the
 * post-refresh hook keeps the graph straight while still surfacing the
 * spawn-tool set well before the first user request can land.
 *
 * <p>Until the refresh event fires the set is empty — which means a
 * trigger-scoped sandbox does not deny anything during early bootstrap.
 * That's the safe direction: production traffic only starts after the
 * context is refreshed, and brain-internal callers ({@code AbstractAiTest}
 * subprocess kicks, for instance) interact through the same lifecycle.
 *
 * <p>{@code AllSpawnToolsAnnotatedTest} verifies via reflection that no
 * spawn-capable tool class slipped past without the annotation.
 */
@Component
@Slf4j
public final class SpawnToolRegistry {

    private final ObjectProvider<List<Tool>> toolsProvider;
    private volatile Set<String> spawnToolNames = Set.of();

    public SpawnToolRegistry(ObjectProvider<List<Tool>> toolsProvider) {
        this.toolsProvider = toolsProvider;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        rescan();
    }

    /** Re-scan the {@link Tool} beans. Idempotent. */
    public synchronized void rescan() {
        List<Tool> tools = toolsProvider.getIfAvailable();
        if (tools == null) {
            this.spawnToolNames = Set.of();
            return;
        }
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
