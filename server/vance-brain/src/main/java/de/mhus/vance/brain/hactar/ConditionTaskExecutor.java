package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarTransition;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pure-logic fan-out (plan §4.6). Walks the state's
 * {@code transitions:} list in order, evaluates each {@code if:}
 * against the current workflow vars/params, returns the first match's
 * {@code to:} target as a {@link TaskOutcome#nextStateOverride}. An
 * {@code else:} branch matches unconditionally and must appear last —
 * the loader enforces that at parse time.
 *
 * <p>Synchronous, single-shot: the executor never returns
 * {@link Optional#empty()}.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ConditionTaskExecutor implements HactarTypeExecutor {

    private final HactarExpressionEvaluator expressions;

    @Override
    public HactarTaskType type() {
        return HactarTaskType.CONDITION_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(HactarTaskContext context) {
        for (HactarTransition transition : context.state().transitions()) {
            if (transition.isElse()
                    || expressions.evaluateBoolean(
                            transition.condition(),
                            context.params(),
                            context.vars(),
                            Map.of())) {
                log.debug("Hactar condition '{}' → {}",
                        transition.isElse() ? "else" : transition.condition(),
                        transition.target());
                return Optional.of(TaskOutcome.chosen(transition.target()));
            }
        }
        return Optional.of(TaskOutcome.failure(
                "condition_task '" + context.state().name()
                        + "' has no matching branch and no else fallback"));
    }
}
