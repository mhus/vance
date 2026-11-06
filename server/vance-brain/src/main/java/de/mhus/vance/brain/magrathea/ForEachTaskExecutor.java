package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaLoopBody;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A loop over a list inside the workflow. The state runs nothing: it decides whether the next element goes into the
 * body ({@code each}) or the loop is over ({@code done}), and hands the element to the body as variables. What the
 * decision is, and how the loop keeps its place, is {@link MagratheaLoop}; this class only runs it for a task and
 * turns its answer into an outcome the dispatcher can journal.
 *
 * <p>Synchronous and single-shot like {@link ConditionTaskExecutor}: it never waits.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class ForEachTaskExecutor implements MagratheaTypeExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.FOR_EACH_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        String name = context.state().name();
        MagratheaLoop.Step step = MagratheaLoop.step(
                name,
                context.state().spec(),
                context.vars(),
                context.previousState(),
                MagratheaLoopBody.of(context.workflow().states(), name));
        if (step.failure() != null) {
            return Optional.of(TaskOutcome.failure(step.failure()));
        }
        Map<String, JsonNode> vars = new LinkedHashMap<>();
        step.vars().forEach((key, value) -> vars.put(key, objectMapper.valueToTree(value)));
        JsonNode output = step.output() == null ? null : objectMapper.valueToTree(step.output());
        return Optional.of(TaskOutcome.withVars(step.outcome(), output, vars));
    }
}
