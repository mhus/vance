package de.mhus.vance.brain.wowbagger.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerCallPolicyTest {

    @Test
    void approved_models_pass_and_calls_are_counted() {
        WorkerCallPolicy policy = new WorkerCallPolicy(r -> "cortecs:deepseek-v4-flash", "*deepseek*,ollama:*", 3);

        policy.beforeLlmCall("ticket-deep");
        policy.beforeDecision("ollama:nimble");

        assertThat(policy.calls()).isEqualTo(2);
    }

    @Test
    void unapproved_model_is_refused_naming_model_and_setting() {
        WorkerCallPolicy policy = new WorkerCallPolicy(r -> "openai:gpt-5", "*deepseek*", 10);

        assertThatThrownBy(() -> policy.beforeLlmCall("expensive"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'openai:gpt-5'")
                .hasMessageContaining("LightLlm recipe 'expensive'")
                .hasMessageContaining("wowbagger.allowed-models");
    }

    @Test
    void no_allowlist_approves_nothing() {
        WorkerCallPolicy policy = new WorkerCallPolicy(r -> "x:y", null, 10);

        assertThatThrownBy(() -> policy.beforeDecision("ollama:nimble")).hasMessageContaining("fail-closed");
    }

    @Test
    void budget_is_enforced_per_chunk() {
        WorkerCallPolicy policy = new WorkerCallPolicy(r -> "ollama:nimble", "ollama:*", 2);
        policy.beforeDecision("ollama:nimble");
        policy.beforeDecision("ollama:nimble");

        assertThatThrownBy(() -> policy.beforeDecision("ollama:nimble"))
                .hasMessageContaining("call budget exhausted — more than 2");
    }
}
