package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardScratchApi;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end of the {@code vance.guard.*} surface through the <b>real</b>
 * GraalJS executor — a guard script drives {@code loopValues} /
 * {@code sessionValues} / {@code continueWith} exactly as the
 * {@code CompletionGuardService} runs it. Complements the pure-Java
 * {@code ScriptGuardApiTest} (which calls the host classes directly) by
 * exercising the actual JS interop (arity-overloaded {@code get},
 * member access, host-object binding) and the "ask once" dedup across
 * two re-entrant runs sharing a host-side scratch map.
 */
class GuardScriptExecutionTest {

    private static Engine engine;
    private static ScriptExecutor executor;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        executor = new GraaljsScriptExecutor(engine);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    /** Records injected prompts and enforces a round cap — like the service's host. */
    private static final class RecordingHost implements GuardScriptHost {
        final List<String> prompts = new ArrayList<>();
        int rounds;
        final int maxRounds;

        RecordingHost(int rounds, int maxRounds) {
            this.rounds = rounds;
            this.maxRounds = maxRounds;
        }

        @Override
        public boolean continueWith(String prompt) {
            if (rounds >= maxRounds) {
                return false;
            }
            rounds++;
            prompts.add(prompt);
            return true;
        }
    }

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(List.of(new RecordingPermissionResolver())),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class));
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");
        return new ContextToolsApi(dispatcher, ctx, Set.of());
    }

    private Object runGuard(String code, String output,
                            Map<String, Object> loopBacking, Map<String, Object> sessionBacking,
                            RecordingHost host) {
        ScriptGuardApi guard = new ScriptGuardApi(
                "the task", output, host.rounds, host.maxRounds, /*naturalStop*/ true,
                new ScriptGuardScratchApi(loopBacking),
                new ScriptGuardScratchApi(sessionBacking),
                host);
        return executor.run(new ScriptRequest(
                        "js", code, "guard-test", tools(), Duration.ofSeconds(5))
                .withGuardApi(guard)).value();
    }

    @Test
    void askOnce_dedupsAcrossReRuns() {
        // Shared host-side scratch across two runs = the re-entrant guard loop.
        Map<String, Object> loop = new ConcurrentHashMap<>();
        Map<String, Object> session = new ConcurrentHashMap<>();
        RecordingHost host = new RecordingHost(0, 5);
        String code =
                "if (!vance.guard.loopValues.get('asked')) {\n"
                + "  vance.guard.loopValues.set('asked', true);\n"
                + "  if (vance.guard.output.indexOf('DONE') < 0)\n"
                + "    vance.guard.continueWith('finish and say DONE');\n"
                + "}\n";

        runGuard(code, "still working", loop, session, host);   // run 1 — output lacks DONE
        runGuard(code, "still working", loop, session, host);   // run 2 — same, but 'asked' is set

        // Asked exactly once despite two completions both lacking DONE.
        assertThat(host.prompts).containsExactly("finish and say DONE");
        assertThat(loop).containsEntry("asked", true);
    }

    @Test
    void continueWith_returnsTrue_whenBelowCap() {
        RecordingHost host = new RecordingHost(0, 3);
        Object result = runGuard(
                "vance.guard.continueWith('nudge');", "x",
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), host);
        assertThat(result).isEqualTo(true);
        assertThat(host.prompts).containsExactly("nudge");
    }

    @Test
    void continueWith_returnsFalse_atCap() {
        RecordingHost host = new RecordingHost(3, 3);   // already at cap
        Object result = runGuard(
                "vance.guard.continueWith('nudge');", "x",
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), host);
        assertThat(result).isEqualTo(false);
        assertThat(host.prompts).isEmpty();
    }

    @Test
    void scratch_getWholeMap_dottedAccessWorks() {
        RecordingHost host = new RecordingHost(0, 3);
        Object result = runGuard(
                "vance.guard.loopValues.set('k', 'v');\n"
                        + "vance.guard.loopValues.get().k;", "x",
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), host);
        assertThat(result).isEqualTo("v");
    }

    @Test
    void loopAndSession_areIndependentStores() {
        Map<String, Object> loop = new ConcurrentHashMap<>();
        Map<String, Object> session = new ConcurrentHashMap<>();
        RecordingHost host = new RecordingHost(0, 3);
        runGuard(
                "vance.guard.loopValues.set('a', 1);\n"
                        + "vance.guard.sessionValues.set('b', 2);", "x",
                loop, session, host);
        assertThat(loop).containsOnlyKeys("a");
        assertThat(session).containsOnlyKeys("b");
    }

    @Test
    void brokenScript_surfacesAsScriptExecutionException() {
        // The service catches this and fails open; here we lock down the
        // contract the service relies on.
        RecordingHost host = new RecordingHost(0, 3);
        assertThatThrownBy(() -> runGuard(
                "this is not valid javascript )(", "x",
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), host))
                .isInstanceOf(ScriptExecutionException.class);
    }
}
