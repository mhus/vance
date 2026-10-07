package de.mhus.vance.brain.recipe;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * A Shooty guard implemented as a Java {@code GuardHandler} bean,
 * referenced from the recipe by its stable {@link #handlerName}. The
 * handler covers <b>all</b> hook points with default no-op methods and
 * simply does not override the ones it does not need — so a handler
 * entry without an explicit {@code trigger} runs at every point and
 * stays free wherever it is a no-op. See
 * {@code specification/public/shooty.md}.
 *
 * <p>Handler-name validation is deliberately <b>lazy</b> (at guard-run
 * time, per-point fail strategy): recipes are tenant/project
 * documents that travel (kits), and the available handler set varies
 * per distribution (addons contribute beans) — a recipe must not
 * brick at load time because one deployment lacks a handler.
 *
 * @param handlerName the {@code GuardHandler#name()} to dispatch to
 *                    (recipe field {@code handler})
 * @param params      inputs handed to the handler via
 *                    {@code GuardContext.params()}
 * @param trigger     optional point narrowing; {@code null} means all
 *                    points (the handler default). YAML: absent or
 *                    {@code trigger: all} — a specific point narrows.
 * @param maxRounds   hard cap on guard injections for the process
 *                    (0 = disabled; only evaluated at STOP/TERMINATE)
 */
public record HandlerGuard(
        String handlerName,
        Map<String, Object> params,
        @Nullable GuardPoint trigger,
        int maxRounds) implements GuardConfig {

    public HandlerGuard {
        if (StringUtils.isBlank(handlerName)) {
            throw new IllegalArgumentException("guard.handler must be a non-blank handler name");
        }
        if (maxRounds < 0) {
            throw new IllegalArgumentException("guard.maxRounds must be >= 0");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    @Override
    public boolean firesOnStart() {
        return trigger == null || trigger.firesOnStart();
    }

    @Override
    public boolean firesOnCommand() {
        return trigger == null || trigger.firesOnCommand();
    }

    @Override
    public boolean firesOnNaturalStop() {
        return trigger == null || trigger.firesOnNaturalStop();
    }

    @Override
    public boolean firesOnTerminate() {
        return trigger == null || trigger.firesOnTerminate();
    }
}
