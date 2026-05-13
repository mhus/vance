package de.mhus.vance.brain.hooks;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Single entry point for executing a hook. {@link HookDispatcher} picks
 * the implementation by {@link HookDef#type()} and hands over the
 * already-built {@link HookHostApi} so neither runner has to reason
 * about scope-binding.
 */
public interface HookRunner {

    HookRunResult run(
            HookDef def,
            HookContext context,
            Map<String, @Nullable Object> eventPayload,
            HookHostApi hostApi);
}
