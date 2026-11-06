package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaLoopBody;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The end of the enclosing block. It runs nothing and has no way on of its own: it sends the run back to the loop
 * whose body it is part of, which then goes on with the next element. The loader has seen to it that there is
 * exactly one such loop (the innermost), so the run is not left standing.
 *
 * <p>Synchronous and single-shot like {@link ConditionTaskExecutor}.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
public class EndTaskExecutor implements MagratheaTypeExecutor {

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.END;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        String name = context.state().name();
        Optional<String> loop;
        try {
            loop = MagratheaLoopBody.enclosingLoop(context.workflow().states(), name);
        } catch (IllegalStateException ex) {
            return Optional.of(TaskOutcome.failure(ex.getMessage()));
        }
        return Optional.of(loop.map(TaskOutcome::chosen)
                .orElseGet(() -> TaskOutcome.failure("end '" + name + "' is not in the body of a loop")));
    }
}
