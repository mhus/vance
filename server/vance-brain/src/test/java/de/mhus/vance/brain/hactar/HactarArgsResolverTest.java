package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HactarArgsResolver}. {@link LightLlmService}
 * is mocked — we exercise the regex scan + diff-against-supplied +
 * LLM-fallback merging logic; the actual LLM call isn't sent.
 */
class HactarArgsResolverTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-1";
    private static final String PROCESS = "proc-1";

    private LightLlmService lightLlm;
    private HactarArgsResolver resolver;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        resolver = new HactarArgsResolver(lightLlm);
    }

    // ──────────────────── extractRequiredKeys ────────────────────

    @Test
    void extract_findsDottedReferences() {
        String code = """
                (function() {
                    var n = vance.params.n;
                    var threshold = vance.params.threshold;
                    return n * threshold;
                })();
                """;

        assertThat(resolver.extractRequiredKeys(code))
                .containsExactly("n", "threshold");
    }

    @Test
    void extract_dedupesRepeatedReferences() {
        String code = """
                if (vance.params.n > 0) {
                    return vance.params.n * vance.params.n;
                }
                """;

        assertThat(resolver.extractRequiredKeys(code))
                .containsExactly("n");
    }

    @Test
    void extract_ignoresMapMethodAccess() {
        String code = """
                if (vance.params.hasOwnProperty('n')) {
                    return vance.params.n;
                }
                """;

        // hasOwnProperty is filtered out; n is real.
        assertThat(resolver.extractRequiredKeys(code))
                .containsExactly("n");
    }

    @Test
    void extract_ignoresBracketNotation() {
        // We intentionally do NOT parse bracket-notation — static
        // analysis is unreliable there. Authors who need dynamic
        // keys must declare them via scriptParams explicitly.
        String code = "var key = 'n'; var v = vance.params[key];";

        assertThat(resolver.extractRequiredKeys(code)).isEmpty();
    }

    @Test
    void extract_emptyForBlankOrNull() {
        assertThat(resolver.extractRequiredKeys(null)).isEmpty();
        assertThat(resolver.extractRequiredKeys("")).isEmpty();
        assertThat(resolver.extractRequiredKeys("var x = 1;")).isEmpty();
    }

    // ──────────────────── resolve — happy paths ────────────────────

    @Test
    void resolve_noReferencesReturnsSuppliedUnchanged() {
        String code = "var x = 42; return x;";
        Map<String, Object> supplied = Map.of("ignored", "value");

        Map<String, Object> resolved = resolver.resolve(
                code, supplied, "irrelevant intent",
                TENANT, PROJECT, PROCESS);

        assertThat(resolved).isEqualTo(supplied);
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resolve_allSuppliedNoLlmCall() {
        String code = "return vance.params.n * vance.params.k;";
        Map<String, Object> supplied = Map.of("n", 7, "k", 3);

        Map<String, Object> resolved = resolver.resolve(
                code, supplied, "intent doesn't matter",
                TENANT, PROJECT, PROCESS);

        assertThat(resolved)
                .containsEntry("n", 7)
                .containsEntry("k", 3);
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resolve_missingExtractedViaLlmAndMerged() {
        String code = "return vance.params.n * vance.params.k;";
        Map<String, Object> supplied = Map.of("k", 3);
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "params", Map.of("n", 2343),
                "unresolved", List.of()));

        Map<String, Object> resolved = resolver.resolve(
                code, supplied, "Quadriere n=2343 mal k",
                TENANT, PROJECT, PROCESS);

        assertThat(resolved)
                .containsEntry("k", 3)          // caller-supplied preserved
                .containsEntry("n", 2343);      // LLM-extracted
    }

    @Test
    void resolve_callerSuppliedWinsOverLlm() {
        String code = "return vance.params.n;";
        Map<String, Object> supplied = Map.of("n", 7);
        // LLM mistakenly returns a different value — caller wins.
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "params", Map.of("n", 999),
                "unresolved", List.of()));

        Map<String, Object> resolved = resolver.resolve(
                code, supplied, "use n",
                TENANT, PROJECT, PROCESS);

        // Caller's value is authoritative; LLM is silently overridden.
        // (The actual call wouldn't even happen because n is supplied —
        // verify with `never`.)
        verify(lightLlm, never()).callForJson(any());
        assertThat(resolved).containsEntry("n", 7);
    }

    // ──────────────────── resolve — failure paths ────────────────────

    @Test
    void resolve_failsLoudWhenNoIntentAndMissing() {
        String code = "return vance.params.n;";

        assertThatThrownBy(() -> resolver.resolve(
                code, Map.of(), /*intent*/ null,
                TENANT, PROJECT, PROCESS))
                .isInstanceOf(HactarArgsResolver.MissingParamException.class)
                .hasMessageContaining("[n]")
                .hasMessageContaining("No process.goal / intent text");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void resolve_failsLoudWhenLlmLeavesUnresolved() {
        String code = "return vance.params.n + vance.params.k;";
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "params", Map.of("n", 7),
                "unresolved", List.of("k")));

        assertThatThrownBy(() -> resolver.resolve(
                code, Map.of(), "n is 7",
                TENANT, PROJECT, PROCESS))
                .isInstanceOf(HactarArgsResolver.MissingParamException.class)
                .hasMessageContaining("[k]");
    }

    @Test
    void resolve_failsLoudWhenLightLlmThrows() {
        String code = "return vance.params.n;";
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("provider 503"));

        assertThatThrownBy(() -> resolver.resolve(
                code, Map.of(), "n is 7",
                TENANT, PROJECT, PROCESS))
                .isInstanceOf(HactarArgsResolver.MissingParamException.class)
                .hasMessageContaining("provider 503");
    }

    // ──────────────────── prompt-variable assembly ────────────────────

    @Test
    void resolve_buildsPromptVarsCorrectly() {
        String code = "return vance.params.n + vance.params.k;";
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "params", new LinkedHashMap<>(Map.of("n", 7, "k", 3)),
                "unresolved", List.of()));

        resolver.resolve(code, Map.of(), "n is 7, k is 3",
                TENANT, PROJECT, PROCESS);

        org.mockito.ArgumentCaptor<LightLlmRequest> captor =
                org.mockito.ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        LightLlmRequest req = captor.getValue();
        assertThat(req.getRecipeName()).isEqualTo("hactar-args-extract");
        assertThat(req.getTenantId()).isEqualTo(TENANT);
        assertThat(req.getProjectId()).isEqualTo(PROJECT);
        assertThat(req.getProcessId()).isEqualTo(PROCESS);
        Map<String, Object> vars = req.getPebbleVars();
        assertThat(vars).containsKeys("code", "intent", "requiredKeys");
        assertThat(vars.get("requiredKeys")).asString()
                .contains("n").contains("k");
    }
}
