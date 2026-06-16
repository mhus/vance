package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Tests the optional {@code vance.process.progress(...)} host-API
 * wiring on {@link VanceScriptApi}. The emitter is injected at
 * construction time; when absent the call is a no-op (so scripts
 * launched in trigger-scoped sandboxes / unit-test stubs degrade
 * gracefully).
 */
class VanceScriptApiProgressTest {

    @Test
    void progress_invokesEmitterWithMessageAndPayload() {
        CapturingEmitter captured = new CapturingEmitter();
        VanceScriptApi api = build(captured);

        api.process.progress(
                "processed 50/200",
                Map.of("processed", 50, "total", 200));

        assertThat(captured.message).isEqualTo("processed 50/200");
        assertThat(captured.payload)
                .containsEntry("processed", 50)
                .containsEntry("total", 200);
        assertThat(captured.calls).isEqualTo(1);
    }

    @Test
    void progress_acceptsNullPayload() {
        CapturingEmitter captured = new CapturingEmitter();
        VanceScriptApi api = build(captured);

        api.process.progress("ping", null);

        assertThat(captured.message).isEqualTo("ping");
        assertThat(captured.payload).isNull();
        assertThat(captured.calls).isEqualTo(1);
    }

    @Test
    void progress_noopWhenEmitterAbsent() {
        // Constructed without an emitter — the trigger-scoped /
        // unit-test path. Calls degrade silently (no NPE, no throw).
        VanceScriptApi api = new VanceScriptApi(
                contextTools("acme", "proj", null, null, null),
                /*recipeName*/ null,
                Set.of(),
                /*documentService*/ null,
                /*progressEmitter*/ null);

        api.process.progress("ping", Map.of("k", "v"));
        api.process.progress("another ping", null);
        // No assertions — we're just verifying no exception escapes.
    }

    @Test
    void progress_rejectsNullMessage() {
        VanceScriptApi api = build(new CapturingEmitter());

        assertThatThrownBy(() -> api.process.progress(null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }

    @Test
    void progress_swallowsEmitterException() {
        // A broken emitter must never leak back into the script —
        // VanceScriptApi catches RuntimeExceptions and logs them.
        AtomicInteger calls = new AtomicInteger(0);
        BiConsumer<String, Map<String, Object>> brokenEmitter = (m, p) -> {
            calls.incrementAndGet();
            throw new RuntimeException("emitter exploded");
        };
        VanceScriptApi api = new VanceScriptApi(
                contextTools("acme", "proj", null, "proc", null),
                null, Set.of(), null, brokenEmitter);

        api.process.progress("boom", null);
        api.process.progress("again", null);

        assertThat(calls.get()).isEqualTo(2);
        // No assertion needed — the test passes iff neither call threw.
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static VanceScriptApi build(
            BiConsumer<String, Map<String, Object>> emitter) {
        return new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                /*recipeName*/ null,
                Set.of(),
                /*documentService*/ null,
                emitter);
    }

    private static ContextToolsApi contextTools(
            String tenant, String project, String session, String process, String user) {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(
                new ToolInvocationContext(tenant, project, session, process, user));
        return tools;
    }

    /** Captures the last (message, payload) pair the script API emitted. */
    private static final class CapturingEmitter
            implements BiConsumer<String, Map<String, Object>> {
        String message;
        Map<String, Object> payload;
        int calls;

        @Override
        public void accept(String m, Map<String, Object> p) {
            this.message = m;
            this.payload = p == null ? null : new LinkedHashMap<>(p);
            this.calls++;
        }
    }
}
