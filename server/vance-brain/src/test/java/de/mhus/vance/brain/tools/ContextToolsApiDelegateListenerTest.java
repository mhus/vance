package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which listener hook a dispatch reports to. The distinction exists so a
 * demand-measuring listener can skip the wrapper→backend leg
 * ({@code file_read} → {@code client_file_read}) without also silencing
 * the progress pings.
 */
class ContextToolsApiDelegateListenerTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "acme", "proj", "sess", "proc", "u");

    @Test
    void llmCall_reportsToTheNormalHooks() {
        stubTool("file_read");
        RecordingListener listener = new RecordingListener();
        ContextToolsApi api = api(listener, Set.of("file_read"));

        api.invoke("file_read", Map.of());

        assertThat(listener.events).containsExactly("before:file_read", "after:file_read");
    }

    @Test
    void delegatedCall_reportsToTheDelegateHooks() {
        stubTool("client_file_read");
        RecordingListener listener = new RecordingListener();
        ContextToolsApi api = api(listener, Set.of("client_file_read"));

        api.invokeDelegate("client_file_read", Map.of());

        assertThat(listener.events).containsExactly(
                "beforeDelegate:client_file_read", "afterDelegate:client_file_read");
    }

    @Test
    void delegatedCall_stillKeepsTheAllowSetGate() {
        stubTool("client_file_read");
        RecordingListener listener = new RecordingListener();
        // Backend not in the dispatch pool → rejected, and nothing is
        // reported: routing a wrapper must not widen what may run.
        ContextToolsApi api = api(listener, Set.of("file_read"));

        assertThat(catchThrowableOf(() -> api.invokeDelegate("client_file_read", Map.of())))
                .isNotNull();
        assertThat(listener.events).isEmpty();
    }

    @Test
    void delegatedFailure_reportsToTheDelegateHook() {
        stubTool("client_file_read");
        when(dispatcher.invoke(eq("client_file_read"), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        RecordingListener listener = new RecordingListener();
        ContextToolsApi api = api(listener, Set.of("client_file_read"));

        assertThat(catchThrowableOf(() -> api.invokeDelegate("client_file_read", Map.of())))
                .isNotNull();
        assertThat(listener.events).contains("afterDelegate:client_file_read");
    }

    private ContextToolsApi api(ToolInvocationListener listener, Set<String> allowed) {
        return new ContextToolsApi(dispatcher, ctx, allowed, listener);
    }

    private void stubTool(String name) {
        Tool tool = new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(
                    Map<String, Object> p, ToolInvocationContext c) { return Map.of(); }
        };
        when(dispatcher.resolve(eq(name), any())).thenReturn(
                Optional.of(new ToolDispatcher.Resolved(tool, source(name, tool))));
        when(dispatcher.invoke(eq(name), any(), any(), any())).thenReturn(Map.of("ok", true));
    }

    private static de.mhus.vance.brain.tools.ToolSource source(String name, Tool tool) {
        return new de.mhus.vance.brain.tools.ToolSource() {
            @Override public String sourceId() { return "stub"; }
            @Override public List<Tool> tools(ToolInvocationContext c) { return List.of(tool); }
            @Override public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(name) ? Optional.of(tool) : Optional.empty();
            }
        };
    }

    private static @Nullable Throwable catchThrowableOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static final class RecordingListener implements ToolInvocationListener {
        final List<String> events = new ArrayList<>();

        @Override public void before(String toolName) {
            events.add("before:" + toolName);
        }

        @Override public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
            events.add("after:" + toolName);
        }

        @Override public void beforeDelegate(String toolName) {
            events.add("beforeDelegate:" + toolName);
        }

        @Override public void afterDelegate(
                String toolName, long elapsedMs, @Nullable Throwable error) {
            events.add("afterDelegate:" + toolName);
        }
    }
}
