package de.mhus.vance.shared.marvin;

import de.mhus.vance.api.marvin.TaskKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * Spring-Data-Mongo read-converter for {@link TaskKind}.
 *
 * <p>Marvin v1 used a 5-value enum (PLAN, EXPAND_FROM_DOC, WORKER,
 * USER_INPUT, AGGREGATE); v2 uses just 3 (WORKER, EXPAND_FROM_DOC,
 * USER_INPUT). Without this converter the default Spring-Mongo
 * enum reader throws {@code IllegalArgumentException} on legacy
 * documents that still carry {@code taskKind: "PLAN"} or
 * {@code "AGGREGATE"}, which kills the entire {@code findNextActionableNode}
 * query — and with it the lane.
 *
 * <p>The mapping legacy → v2:
 * <ul>
 *   <li>{@code PLAN}      → {@code WORKER} (root-decomposition is now
 *       a worker's SCOPE phase)</li>
 *   <li>{@code AGGREGATE} → {@code WORKER} (synthesis is now a
 *       worker's POST_CHILDREN / CONCLUDE phase)</li>
 *   <li>Anything else unrecognised → {@code WORKER}</li>
 * </ul>
 *
 * <p>A WARN-log surfaces every legacy hit so the operator notices
 * the migration debt. The legacy node's {@code artifacts} stay
 * intact — only the type discriminator collapses. Behaviour-wise
 * the node will just be skipped (it's already in a terminal status
 * from its v1 lifetime), but the rest of the tree can still be
 * read and rendered.
 */
@ReadingConverter
@Slf4j
public class TaskKindReadConverter implements Converter<String, TaskKind> {

    @Override
    public TaskKind convert(String source) {
        if (source == null || source.isBlank()) return TaskKind.WORKER;
        try {
            return TaskKind.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Legacy/unknown TaskKind value '{}' in marvin_nodes — "
                    + "mapped to WORKER. Run the v1→v2 cleanup if this "
                    + "is recurrent (see specification/marvin-engine.md §1.1).",
                    source);
            return TaskKind.WORKER;
        }
    }
}
