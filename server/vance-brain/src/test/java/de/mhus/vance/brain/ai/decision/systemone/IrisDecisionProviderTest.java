package de.mhus.vance.brain.ai.decision.systemone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IrisDecisionProviderTest {

    private final SystemOneWireClient wire = mock(SystemOneWireClient.class);
    private final IrisDecisionProvider provider = new IrisDecisionProvider(wire);

    private static final DecisionRequest REQUEST = new DecisionRequest(
            "state",
            List.of(ZemQuestionDto.builder()
                    .name("q")
                    .type(ZemQuestionType.NOUL)
                    .instructions("?")
                    .build()));

    @Test
    void itIsSelectedByTheIrisProtocol() {
        assertThat(provider.getType()).isEqualTo(ProviderType.IRIS);
        assertThat(provider.getInstanceName()).isEqualTo(Optional.empty());
    }

    @Test
    void theEndpointIsTheConfiguredBaseUrlPlusSystemOne() {
        assertThat(provider.endpoint("https://iris.example/v1"))
                .isEqualTo(URI.create("https://iris.example/v1/systemone"));
        assertThat(provider.endpoint("https://iris.example"))
                .isEqualTo(URI.create("https://iris.example/v1/systemone"));
    }

    @Test
    void thereIsNoFallbackEndpoint() {
        assertThatThrownBy(() -> provider.endpoint(null))
                .isInstanceOf(AiDecisionException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void theVendorCostOfAnAnswerIsDropped() {
        AiDecisionConfig config =
                new AiDecisionConfig("iris", "iris", "eu-decide", "vk", "https://iris.example/v1", 30);
        DecisionResult vendor = new DecisionResult("m", List.of(), 36L, 1L, 0.0000015, "gen-1");
        when(wire.post(any(URI.class), eq("vk"), eq(config), eq(REQUEST))).thenReturn(vendor);

        DecisionResult result = provider.decide(config, REQUEST);

        assertThat(result.costUsd()).isNull();
        assertThat(result.inputTokens()).isEqualTo(36L);
        assertThat(result.requestId()).isEqualTo("gen-1");
    }
}
