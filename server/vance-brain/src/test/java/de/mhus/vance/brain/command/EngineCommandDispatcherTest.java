package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineCommandDispatcherTest {

    private final MetricService metrics = new MetricService(new SimpleMeterRegistry());

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder().id("p1").build();
    }

    private EngineCommandDispatcher dispatcher(EngineCommandHandler... handlers) {
        return new EngineCommandDispatcher(List.of(handlers), metrics);
    }

    @Test
    void dispatch_unknownVerb_returnsUnknownAndDoesNotThrow() {
        EngineCommandDispatcher dispatcher = dispatcher();

        EngineCommandResult result = dispatcher.dispatch(
                process(), new EngineCommand("does.not.exist", Map.of()));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.UNKNOWN);
        assertThat(result.message()).contains("does.not.exist");
        assertThat(result.value()).isNull();
    }

    @Test
    void dispatch_registeredHandler_routesAndReturnsItsResult() {
        EngineCommandHandler ok = handler("do.it",
                (p, c) -> EngineCommandResult.ok("done", c.args()));
        EngineCommandDispatcher dispatcher = dispatcher(ok);

        EngineCommandResult result = dispatcher.dispatch(
                process(), new EngineCommand("do.it", Map.of("k", "v")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).isEqualTo("done");
        assertThat(result.value()).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void dispatch_handlerThrows_mappedToErrorNotPropagated() {
        EngineCommandHandler boom = handler("boom", (p, c) -> {
            throw new IllegalStateException("kaboom");
        });
        EngineCommandDispatcher dispatcher = dispatcher(boom);

        EngineCommandResult result = dispatcher.dispatch(
                process(), new EngineCommand("boom", Map.of()));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).isEqualTo("kaboom");
    }

    @Test
    void dispatch_handlerReturnsNull_mappedToError() {
        EngineCommandHandler nully = handler("nully", (p, c) -> null);
        EngineCommandDispatcher dispatcher = dispatcher(nully);

        EngineCommandResult result = dispatcher.dispatch(
                process(), new EngineCommand("nully", Map.of()));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    @Test
    void construct_duplicateVerb_failsFast() {
        EngineCommandHandler a = handler("dup", (p, c) -> EngineCommandResult.ok());
        EngineCommandHandler b = handler("dup", (p, c) -> EngineCommandResult.ok());

        assertThatThrownBy(() -> dispatcher(a, b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void echoHandler_returnsArgsVerbatim() {
        EngineCommandDispatcher dispatcher = dispatcher(new SystemEchoCommandHandler());

        EngineCommandResult result = dispatcher.dispatch(
                process(), new EngineCommand("echo", Map.of("msg", "hi")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.value()).isEqualTo(Map.of("msg", "hi"));
    }

    private interface HandleFn {
        EngineCommandResult apply(ThinkProcessDocument process, EngineCommand command);
    }

    private static EngineCommandHandler handler(String verb, HandleFn fn) {
        return new EngineCommandHandler() {
            @Override
            public String verb() {
                return verb;
            }

            @Override
            public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
                return fn.apply(process, command);
            }
        };
    }
}
