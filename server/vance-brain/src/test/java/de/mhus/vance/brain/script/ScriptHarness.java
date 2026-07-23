package de.mhus.vance.brain.script;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.graalvm.polyglot.Engine;
import org.slf4j.LoggerFactory;

/**
 * Test-time harness for skill-bound JavaScript scripts. Lets you
 * iterate on a script body in &lt; 500 ms — no Brain bootstrap, no
 * Mongo container, no LLM round-trip. Wraps {@link GraaljsScriptExecutor}
 * with a Mockito-backed {@link ContextToolsApi} that intercepts
 * {@code vance.tools.call(...)} invocations and lets the test
 * <em>configure</em> the response per tool name.
 *
 * <p>Typical use:
 * <pre>{@code
 * ScriptHarness harness = ScriptHarness.builder()
 *     .scriptFile(Path.of("../../qa/kits/hello-script-kit/.../greet.js"))
 *     .args(Map.of("name", "Klaus"))
 *     .mockTool("doc_write_text", params -> Map.of("size", 42))
 *     .build();
 *
 * ScriptResult result = harness.run();
 * assertThat(harness.toolCalls()).extracting(ToolCall::name)
 *     .containsExactly("doc_write_text");
 * assertThat(harness.logRecords()).hasSize(1);
 * }</pre>
 *
 * <p>Coverage and limits:
 * <ul>
 *   <li><b>What this tests:</b> script syntax (GraalJS rejects with line
 *       number), top-level {@code args} binding, {@code vance.tools.call}
 *       call site shape (name + params), {@code vance.log} side effect,
 *       return-value structure.</li>
 *   <li><b>What this doesn't test:</b> the LLM ever <i>picking</i> the
 *       virtual tool — that's still the E2E test's job. Real
 *       {@code ToolDispatcher} cascade (skill mounting, permissions,
 *       quota) — the harness fakes its own.</li>
 * </ul>
 */
public final class ScriptHarness {

    /** Logger name {@code VanceScriptApi.ScriptLog} writes to. The
     *  harness attaches a {@link ListAppender} here to capture
     *  {@code vance.log.{info,warn,error}} calls. */
    private static final String SCRIPT_LOGGER_NAME = "vance.script";

    private final String code;
    private final String sourceName;
    private final Map<String, Object> args;
    private final ToolInvocationContext scope;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> toolMocks;
    private final Duration timeout;

    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private ScriptHarness(Builder b) {
        this.code = b.code;
        this.sourceName = b.sourceName;
        this.args = b.args == null ? Map.of() : Map.copyOf(b.args);
        this.scope = b.scope;
        this.toolMocks = Map.copyOf(b.toolMocks);
        this.timeout = b.timeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Run the script once. The same harness instance can be re-used
     *  but its recorded {@link #toolCalls()} / {@link #logRecords()}
     *  accumulate across runs — call {@link #reset()} between runs to
     *  clear them. */
    public ScriptResult run() {
        // Wire logback list-appender to capture vance.log.* calls.
        // Idempotent — re-attaching the same appender no-ops in logback.
        Logger scriptLog = (Logger) LoggerFactory.getLogger(SCRIPT_LOGGER_NAME);
        logAppender.start();
        scriptLog.addAppender(logAppender);

        try (Engine engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            ScriptExecutor executor = new GraaljsScriptExecutor(engine);
            ContextToolsApi tools = buildRecordingTools();
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("args", args);
            ScriptRequest req = new ScriptRequest(
                    "js", code, sourceName, tools, timeout, bindings);
            return executor.run(req);
        } finally {
            scriptLog.detachAppender(logAppender);
        }
    }

    /** Recorded {@code vance.tools.call(name, params)} invocations,
     *  in calling order. */
    public List<ToolCall> toolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    /** Convenience — find the (last) recorded call to {@code name},
     *  or {@code null} if none. */
    public ToolCall lastCall(String name) {
        for (int i = toolCalls.size() - 1; i >= 0; i--) {
            if (toolCalls.get(i).name().equals(name)) return toolCalls.get(i);
        }
        return null;
    }

    /** Recorded {@code vance.log.{info,warn,error}} events. */
    public List<ILoggingEvent> logRecords() {
        return Collections.unmodifiableList(logAppender.list);
    }

    /** Clear the recorded tool calls + log events. Use when running
     *  the same script twice with different args. */
    public void reset() {
        toolCalls.clear();
        logAppender.list.clear();
    }

    // ──────────────────── Internals ────────────────────

    private ContextToolsApi buildRecordingTools() {
        // RecordingToolSource yields one fake tool per mockTool entry.
        // The ContextToolsApi wrap goes through the real ToolDispatcher
        // so the same allow-filter / re-entry path the production
        // executor uses is exercised here too.
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("harness");
        List<Tool> fakes = new ArrayList<>();
        for (Map.Entry<String, Function<Map<String, Object>, Map<String, Object>>> e
                : toolMocks.entrySet()) {
            fakes.add(new RecordingTool(e.getKey(), e.getValue(), toolCalls));
        }
        when(src.tools(any())).thenReturn(fakes);
        when(src.find(any(), any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return fakes.stream().filter(t -> t.name().equals(name)).findFirst();
        });
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(java.util.List.of(new RecordingPermissionResolver())),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class));
        // ContextToolsApi's allow-filter set: include every mocked tool
        // by name so vance.tools.call(...) doesn't get filtered out.
        Set<String> allowed = new LinkedHashSet<>(toolMocks.keySet());
        return new ContextToolsApi(dispatcher, scope, allowed);
    }

    /** One recorded {@code vance.tools.call} invocation. */
    public record ToolCall(String name, Map<String, Object> params) {
    }

    /** A Tool implementation that records the params + delegates to
     *  the test-supplied response function. Returns whatever the
     *  function returns; null gets normalised to an empty map. */
    private static final class RecordingTool implements Tool {
        private final String name;
        private final Function<Map<String, Object>, Map<String, Object>> body;
        private final List<ToolCall> sink;

        RecordingTool(String name,
                Function<Map<String, Object>, Map<String, Object>> body,
                List<ToolCall> sink) {
            this.name = name;
            this.body = body;
            this.sink = sink;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "harness:" + name; }
        @Override public boolean primary() { return true; }
        @Override public Map<String, Object> paramsSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            Map<String, Object> captured = params == null
                    ? Map.of() : Map.copyOf(params);
            sink.add(new ToolCall(name, captured));
            try {
                Map<String, Object> reply = body.apply(captured);
                return reply == null ? Map.of() : reply;
            } catch (RuntimeException e) {
                throw new ToolException(
                        "harness mock for '" + name + "' threw: " + e.getMessage(), e);
            }
        }
    }

    // ──────────────────── Builder ────────────────────

    public static final class Builder {

        private String code;
        private String sourceName = "harness:<inline>";
        private Map<String, Object> args = Map.of();
        private ToolInvocationContext scope = new ToolInvocationContext(
                "acme", "test-proj", "test-sess", "test-proc-" + shortId(), "tester");
        private final Map<String, Function<Map<String, Object>, Map<String, Object>>> toolMocks
                = new LinkedHashMap<>();
        private Duration timeout = Duration.ofSeconds(10);

        public Builder script(String inlineCode) {
            this.code = inlineCode;
            return this;
        }

        public Builder scriptFile(Path path) throws IOException {
            this.code = Files.readString(path);
            this.sourceName = "harness:" + path.getFileName();
            return this;
        }

        public Builder args(Map<String, Object> args) {
            this.args = args;
            return this;
        }

        public Builder scope(ToolInvocationContext ctx) {
            this.scope = ctx;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Register a mock implementation for {@code vance.tools.call(name, …)}.
         * The function receives the JS-supplied params (already converted
         * to a Java Map by the executor) and returns the tool-result.
         * Returning {@code null} is treated as an empty map.
         */
        public Builder mockTool(
                String name,
                Function<Map<String, Object>, Map<String, Object>> response) {
            this.toolMocks.put(name, response);
            return this;
        }

        public ScriptHarness build() {
            if (code == null || code.isBlank()) {
                throw new IllegalStateException(
                        "ScriptHarness needs either .script(inlineCode) or .scriptFile(path)");
            }
            return new ScriptHarness(this);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
