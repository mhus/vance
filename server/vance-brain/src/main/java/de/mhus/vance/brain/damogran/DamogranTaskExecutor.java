package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Registry + dispatcher over all {@link DamogranTask} beans, keyed by
 * {@link DamogranTask#type()}. Mirrors {@code MagratheaTaskExecutor}: a
 * duplicate type is a wiring error and fails fast at construction.
 *
 * <p>Dispatch never throws for ordinary task failures — an unknown type or a
 * task that threw is turned into a {@link DamogranTaskResult#failure(String)}
 * so the error rides the result envelope and the linear run halts cleanly.
 */
@Slf4j
@Service
public class DamogranTaskExecutor {

    private final Map<String, DamogranTask> byType;

    public DamogranTaskExecutor(List<DamogranTask> tasks) {
        Map<String, DamogranTask> map = new HashMap<>();
        for (DamogranTask task : tasks) {
            DamogranTask prev = map.put(task.type(), task);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate DamogranTask type '" + task.type() + "': "
                                + prev.getClass().getName() + " and " + task.getClass().getName());
            }
        }
        this.byType = Map.copyOf(map);
        log.debug("DamogranTaskExecutor: {} task type(s) registered: {}",
                byType.size(), new TreeSet<>(byType.keySet()));
    }

    /** The registered task types (sorted), for diagnostics and discovery. */
    public TreeSet<String> types() {
        return new TreeSet<>(byType.keySet());
    }

    public DamogranTaskResult dispatch(DamogranContext ctx, TaskSpec spec) {
        DamogranTask task = byType.get(spec.type());
        if (task == null) {
            return DamogranTaskResult.failure(
                    "unknown task type '" + spec.type() + "' (known: " + types() + ")");
        }
        try {
            return task.execute(ctx, spec);
        } catch (RuntimeException e) {
            log.warn("Damogran task '{}' threw", spec.type(), e);
            return DamogranTaskResult.failure(
                    "task '" + spec.type() + "' error: " + e.getMessage());
        }
    }
}
