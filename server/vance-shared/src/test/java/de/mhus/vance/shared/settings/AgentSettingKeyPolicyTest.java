package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** What an agent may never write when the operator has not configured the list. */
class AgentSettingKeyPolicyTest {

    private final AgentSettingKeyPolicy policy = new AgentSettingKeyPolicy(AgentSettingKeyPolicy.DEFAULT_DENY_KEYS);

    @Test
    void costBudget_isDenied() {
        // An agent that could write these would raise its own spending limit.
        assertThat(policy.isDenied("ai.llm.budget.daily")).isTrue();
        assertThat(policy.isDenied("ai.llm.budget.monthly")).isTrue();
        assertThat(policy.isDenied("ai.llm.budget.total")).isTrue();
        assertThat(policy.isDenied("ai.llm.budget.total-since")).isTrue();
        assertThat(policy.isDenied("ai.llm.budget.warn-percent")).isTrue();
        assertThatThrownBy(() -> policy.requireAgentWritable("ai.llm.budget.daily"))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void infrastructureFamilies_stayDenied() {
        assertThat(policy.isDenied("ai.provider.openai.apiKey")).isTrue();
        assertThat(policy.isDenied("vault.type")).isTrue();
        assertThat(policy.isDenied("store.token.acme")).isTrue();
        assertThat(policy.isDenied("kit.token.acme")).isTrue();
    }

    @Test
    void ordinarySettings_remainWritable() {
        assertThat(policy.isDenied("ai.default.model")).isFalse();
        assertThat(policy.isDenied("ai.llm.other")).isFalse();
        assertThat(policy.isDenied("chat.language")).isFalse();
    }
}
