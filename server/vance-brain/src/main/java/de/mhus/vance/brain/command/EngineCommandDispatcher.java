package de.mhus.vance.brain.command;

import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Central router for control-plane {@link EngineCommand}s. Indexes every
 * {@link EngineCommandHandler} bean by its {@link EngineCommandHandler#verb()}
 * and dispatches on the process lane.
 *
 * <p>Contract (see {@code planning/engine-commands.md} §2.4):
 * <ul>
 *   <li>unknown verb → {@link EngineCommandResult#unknown} + WARN — a
 *       defined no-op, never a crash;</li>
 *   <li>handler throws → {@link EngineCommandResult#error} + WARN;</li>
 *   <li>otherwise the handler's own result.</li>
 * </ul>
 *
 * <p>The verb vocabulary is deliberately empty in the foundation layer
 * apart from the diagnostic {@code echo} verb — real verbs are added per
 * engine later.
 */
@Component
@Slf4j
public class EngineCommandDispatcher {

    private static final String METRIC = "vance.engine.commands";

    private final Map<String, EngineCommandHandler> handlers;
    private final MetricService metrics;

    public EngineCommandDispatcher(List<EngineCommandHandler> handlerBeans, MetricService metrics) {
        Map<String, EngineCommandHandler> index = new HashMap<>();
        for (EngineCommandHandler handler : handlerBeans) {
            String verb = handler.verb();
            if (verb == null || verb.isBlank()) {
                throw new IllegalStateException(
                        "EngineCommandHandler " + handler.getClass().getName()
                                + " declares a blank verb");
            }
            EngineCommandHandler previous = index.putIfAbsent(verb, handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate EngineCommandHandler for verb '" + verb + "': "
                                + previous.getClass().getName() + " and "
                                + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(index);
        this.metrics = metrics;
        log.info("EngineCommandDispatcher ready with {} verb(s): {}",
                handlers.size(), handlers.keySet());
    }

    /**
     * Whether the handler for {@code verb} opts out of the addressed
     * process's lane.
     *
     * <p>Phrased as the negative on purpose: only an explicit
     * {@code runsOnLane() == false} from a known handler skips
     * serialization. Anything else — unknown verb, absent handler, a test
     * double answering with its zero-value — lands on the lane, which is
     * the safe side.
     */
    public boolean bypassesLane(String verb) {
        EngineCommandHandler handler = handlers.get(verb);
        return handler != null && !handler.runsOnLane();
    }

    /**
     * Routes {@code command} to its handler. Never throws — every failure
     * mode maps onto an {@link EngineCommandResult}.
     */
    public EngineCommandResult dispatch(ThinkProcessDocument process, EngineCommand command) {
        String verb = command.name();
        EngineCommandHandler handler = handlers.get(verb);
        if (handler == null) {
            log.warn("Engine command verb='{}' has no handler (process id='{}') — no-op",
                    verb, process.getId());
            // No `verb` tag: unknown verbs are caller-supplied free text
            // → unbounded cardinality (see CLAUDE.md metrics rules).
            metrics.counter(METRIC, "outcome", "unknown").increment();
            return EngineCommandResult.unknown(
                    "No handler registered for command '" + verb + "'");
        }
        try {
            EngineCommandResult result = handler.handle(process, command);
            if (result == null) {
                log.warn("Engine command verb='{}' handler returned null (process id='{}')",
                        verb, process.getId());
                metrics.counter(METRIC, "verb", verb, "outcome", "error").increment();
                return EngineCommandResult.error("Handler returned no result");
            }
            metrics.counter(METRIC, "verb", verb,
                    "outcome", result.outcome().name().toLowerCase(Locale.ROOT)).increment();
            return result;
        } catch (RuntimeException e) {
            log.warn("Engine command verb='{}' failed (process id='{}'): {}",
                    verb, process.getId(), e.toString(), e);
            metrics.counter(METRIC, "verb", verb, "outcome", "error").increment();
            return EngineCommandResult.error(messageOf(e));
        }
    }

    private static String messageOf(RuntimeException e) {
        @Nullable String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
