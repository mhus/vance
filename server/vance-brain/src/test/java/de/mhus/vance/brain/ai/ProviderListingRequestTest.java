package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderListingRequestTest {

    @Test
    void toString_masksTheApiKey() {
        ProviderListingRequest req = new ProviderListingRequest("openrouter", "sk-secret-123", null);

        assertThat(req.toString()).doesNotContain("sk-secret-123").contains("key=***");
    }

    @Test
    void toString_saysNoneWithoutAKey() {
        ProviderListingRequest req = new ProviderListingRequest("ollama", null, "http://localhost:11434");

        assertThat(req.toString()).contains("key=none");
    }
}
