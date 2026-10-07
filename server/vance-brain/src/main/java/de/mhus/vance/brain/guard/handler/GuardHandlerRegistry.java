package de.mhus.vance.brain.guard.handler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Collects every {@link GuardHandler} bean in the application context
 * into a name→handler map — the lookup the {@code ShootyGuardService}
 * uses to dispatch a recipe's {@code handler:} entries. Addons
 * contribute handlers simply by registering beans.
 *
 * <p>Duplicate names fail the boot: a name is a recipe's stable
 * reference, and two beans claiming it would silently pick one.
 * Handler-name validation in recipes is deliberately lazy (at guard-run
 * time) because the available set varies per distribution — see
 * {@code HandlerGuard}.
 */
@Service
public class GuardHandlerRegistry {

    private final Map<String, GuardHandler> handlers;

    public GuardHandlerRegistry(List<GuardHandler> handlers) {
        Map<String, GuardHandler> byName = new LinkedHashMap<>();
        for (GuardHandler handler : handlers) {
            GuardHandler previous = byName.putIfAbsent(handler.name(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate guard handler name '" + handler.name() + "' ("
                        + previous.getClass().getName() + " vs "
                        + handler.getClass().getName() + ")");
            }
        }
        this.handlers = Collections.unmodifiableMap(byName);
    }

    /** The handler registered under {@code name}, or {@code null}. */
    public @Nullable GuardHandler find(String name) {
        return handlers.get(name);
    }

    /** All registered handler names, in registration order. */
    public Set<String> names() {
        return handlers.keySet();
    }
}
