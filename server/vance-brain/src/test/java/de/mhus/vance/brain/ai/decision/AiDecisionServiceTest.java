package de.mhus.vance.brain.ai.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.ProviderType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Instance-before-protocol dispatch, same contract as {@code AiAudioService}. */
class AiDecisionServiceTest {

    private static final DecisionRequest REQUEST = new DecisionRequest(
            "x",
            List.of(ZemQuestionDto.builder()
                    .name("q")
                    .type(ZemQuestionType.NOUL)
                    .instructions("?")
                    .build()));

    @Test
    void instance_provider_wins_over_the_protocol_type() {
        AiDecisionService service = service(
                new Fake(ProviderType.OPENAI, "openrouter", "openrouter-adapter"),
                new Fake(ProviderType.OPENAI, null, "openai-adapter"));

        DecisionResult viaInstance = service.decide(config("openai", "openrouter"), REQUEST);
        DecisionResult viaType = service.decide(config("openai", "openai"), REQUEST);

        assertThat(viaInstance.model()).isEqualTo("openrouter-adapter");
        assertThat(viaType.model()).isEqualTo("openai-adapter");
    }

    @Test
    void named_instance_without_own_adapter_falls_back_to_its_protocol() {
        AiDecisionService service = service(new Fake(ProviderType.OLLAMA, null, "ollama-adapter"));

        assertThat(service.decide(config("ollama", "gpu-box"), REQUEST).model()).isEqualTo("ollama-adapter");
        assertThat(service.canServe("gpu-box", "ollama")).isTrue();
        assertThat(service.canServe("anthropic", "anthropic")).isFalse();
    }

    @Test
    void unserved_provider_is_a_no_adapter_failure() {
        AiDecisionService service = service(new Fake(ProviderType.OLLAMA, null, "ollama-adapter"));

        assertThatThrownBy(() -> service.decide(config("anthropic", "anthropic"), REQUEST))
                .isInstanceOfSatisfying(AiDecisionException.class, e -> {
                    assertThat(e.failure()).isEqualTo(AiDecisionException.Failure.NO_ADAPTER);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void duplicate_registrations_fail_at_startup() {
        AiDecisionService twoTypes = new AiDecisionService(
                List.of(new Fake(ProviderType.OLLAMA, null, "a"), new Fake(ProviderType.OLLAMA, null, "b")));
        assertThatThrownBy(twoTypes::postConstruct).isInstanceOf(IllegalStateException.class);

        AiDecisionService twoInstances = new AiDecisionService(List.of(
                new Fake(ProviderType.OPENAI, "openrouter", "a"), new Fake(ProviderType.OPENAI, "openrouter", "b")));
        assertThatThrownBy(twoInstances::postConstruct).isInstanceOf(IllegalStateException.class);
    }

    private static AiDecisionService service(AiDecisionModelProvider... providers) {
        AiDecisionService service = new AiDecisionService(List.of(providers));
        service.postConstruct();
        return service;
    }

    private static AiDecisionConfig config(String protocol, String instance) {
        return new AiDecisionConfig(protocol, instance, "m", "k", null, 10);
    }

    /** Answers with its own label as the model id, so the test sees who was picked. */
    private record Fake(ProviderType type, String instance, String label) implements AiDecisionModelProvider {

        @Override
        public ProviderType getType() {
            return type;
        }

        @Override
        public Optional<String> getInstanceName() {
            return Optional.ofNullable(instance);
        }

        @Override
        public DecisionResult decide(AiDecisionConfig config, DecisionRequest request) {
            return new DecisionResult(label, List.of(), null, null, null, null);
        }
    }
}
