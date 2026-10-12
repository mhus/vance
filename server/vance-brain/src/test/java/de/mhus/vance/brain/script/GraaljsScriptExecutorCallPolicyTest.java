package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
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
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@link ScriptCallPolicy} travels from the {@link ScriptRequest} through
 * the real GraalJS executor into {@code vance.llm} inside the script — the
 * wiring the Wowbagger script worker's model gate depends on.
 */
class GraaljsScriptExecutorCallPolicyTest {

    private static Engine engine;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src),
                new PermissionService(List.of(new RecordingPermissionResolver())),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class),
                mock(de.mhus.vance.brain.tools.ToolGuardGate.class));
        return new ContextToolsApi(
                dispatcher, new ToolInvocationContext("acme", "p1", "s1", "proc-1", "alice"), Set.of());
    }

    private static ScriptRequest request(String code, ScriptCallPolicy policy) {
        return new ScriptRequest(
                        "js",
                        code,
                        "policy-test",
                        tools(),
                        Duration.ofSeconds(5),
                        Map.of(),
                        null,
                        ScopeLevel.PROCESS_SCOPED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)
                .withCallPolicy(policy);
    }

    @Test
    void policy_is_consulted_inside_the_script_and_a_refusal_fails_the_run() {
        LightLlmService llm = mock(LightLlmService.class);
        when(llm.call(any(LightLlmRequest.class))).thenReturn("fine");
        List<String> seen = new ArrayList<>();
        ScriptCallPolicy policy = new ScriptCallPolicy() {
            @Override
            public void beforeLlmCall(String recipeName) {
                seen.add(recipeName);
                if ("expensive".equals(recipeName)) {
                    throw new IllegalStateException("model not approved");
                }
            }

            @Override
            public void beforeDecision(String resolvedModel) {}
        };
        GraaljsScriptExecutor executor = new GraaljsScriptExecutor(
                engine,
                new ScriptEngineConfig().scriptHostAccess(),
                new ScriptEngineProperties(),
                null,
                null,
                null,
                llm);

        assertThat(executor.run(request("vance.llm.call('cheap', 'x')", policy)).value())
                .isEqualTo("fine");
        assertThatThrownBy(() -> executor.run(request("vance.llm.call('expensive', 'x')", policy)))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("refused: model not approved");

        assertThat(seen).containsExactly("cheap", "expensive");
        verify(llm, never()).call(org.mockito.ArgumentMatchers.argThat(r -> "expensive".equals(r.getRecipeName())));
    }
}
