package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code vance.llm} consults the run's {@link ScriptCallPolicy} before every call. */
class ScriptLightLlmPolicyTest {

    private static final ToolInvocationContext SCOPE = new ToolInvocationContext("acme", "p1", "s1", "proc-1", "mara");

    private final LightLlmService service = mock(LightLlmService.class);
    private final ScriptCallPolicy policy = mock(ScriptCallPolicy.class);

    @Test
    void approved_call_goes_through() {
        when(service.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of("ok", true));
        VanceScriptApi.ScriptLightLlmApi api = new VanceScriptApi.ScriptLightLlmApi(service, SCOPE, policy);

        assertThat(api.callForJson("ticket-deep", "x")).containsEntry("ok", true);
        verify(policy).beforeLlmCall("ticket-deep");
    }

    @Test
    void refused_call_is_never_made() {
        doThrow(new IllegalStateException("call budget exhausted")).when(policy).beforeLlmCall("ticket-deep");
        VanceScriptApi.ScriptLightLlmApi api = new VanceScriptApi.ScriptLightLlmApi(service, SCOPE, policy);

        assertThatThrownBy(() -> api.call("ticket-deep", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("vance.llm(ticket-deep) refused: call budget exhausted");
        verify(service, never()).call(any(LightLlmRequest.class));
    }

    @Test
    void without_policy_nothing_is_checked() {
        when(service.call(any(LightLlmRequest.class))).thenReturn("ok");
        VanceScriptApi.ScriptLightLlmApi api = new VanceScriptApi.ScriptLightLlmApi(service, SCOPE, null);

        assertThat(api.call("any", "x")).isEqualTo("ok");
    }
}
