package de.mhus.vance.brain.ai.decision.systemone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Endpoint rule and key policy of the three System-One providers — the
 * only things they decide themselves.
 */
class SystemOneProviderSupportTest {

    private static final DecisionRequest REQUEST = new DecisionRequest(
            "x",
            List.of(ZemQuestionDto.builder()
                    .name("q")
                    .type(ZemQuestionType.NOUL)
                    .instructions("?")
                    .build()));

    private final SystemOneWireClient wire = mock(SystemOneWireClient.class);

    @Test
    void endpoint_rule_covers_all_three_base_url_conventions() {
        OpenRouterDecisionProvider openRouter = new OpenRouterDecisionProvider(wire, "");
        OllamaDecisionProvider ollama = new OllamaDecisionProvider(wire, "http://localhost:11434");
        DirectSystemOneProvider direct = new DirectSystemOneProvider(wire);

        assertThat(openRouter.endpoint(null)).isEqualTo(URI.create("https://openrouter.ai/api/v1/systemone"));
        assertThat(openRouter.endpoint("https://openrouter.ai/api/v1/"))
                .isEqualTo(URI.create("https://openrouter.ai/api/v1/systemone"));
        assertThat(ollama.endpoint(null)).isEqualTo(URI.create("http://localhost:11434/v1/systemone"));
        assertThat(ollama.endpoint("http://gpu-box:11434/v1"))
                .isEqualTo(URI.create("http://gpu-box:11434/v1/systemone"));
        assertThat(direct.endpoint(null)).isEqualTo(URI.create("https://api.typesafe.ai/v1/systemone"));
        assertThat(direct.endpoint("https://api.upstage.ai/v1"))
                .isEqualTo(URI.create("https://api.upstage.ai/v1/systemone"));
    }

    @Test
    void openrouter_refuses_to_call_without_a_key() {
        OpenRouterDecisionProvider provider = new OpenRouterDecisionProvider(wire, "");
        AiDecisionConfig noKey = new AiDecisionConfig("openai", "openrouter", "typesafe/jev-1.13", null, null, 10);

        assertThatThrownBy(() -> provider.decide(noKey, REQUEST))
                .isInstanceOfSatisfying(
                        AiDecisionException.class,
                        e -> assertThat(e.failure()).isEqualTo(AiDecisionException.Failure.AUTH))
                .hasMessageContaining("ai.provider.openrouter.apiKey");
        verifyNoInteractions(wire);
    }

    @Test
    void ollama_and_direct_endpoints_call_without_a_key() {
        AiDecisionConfig local = new AiDecisionConfig("ollama", "ollama", "nimble", null, null, 30);
        new OllamaDecisionProvider(wire, "http://localhost:11434").decide(local, REQUEST);
        verify(wire).post(eq(URI.create("http://localhost:11434/v1/systemone")), isNull(), eq(local), any());

        AiDecisionConfig laya = new AiDecisionConfig("systemone", "laya", "laya", null, "http://localhost:8000", 30);
        new DirectSystemOneProvider(wire).decide(laya, REQUEST);
        verify(wire).post(eq(URI.create("http://localhost:8000/v1/systemone")), isNull(), eq(laya), any());
    }

    @Test
    void dispatch_keys() {
        assertThat(new OpenRouterDecisionProvider(wire, "").getInstanceName()).contains("openrouter");
        assertThat(new OllamaDecisionProvider(wire, "x").getType()).isEqualTo(ProviderType.OLLAMA);
        assertThat(new OllamaDecisionProvider(wire, "x").getInstanceName()).isEmpty();
        assertThat(new DirectSystemOneProvider(wire).getType()).isEqualTo(ProviderType.SYSTEMONE);
    }
}
