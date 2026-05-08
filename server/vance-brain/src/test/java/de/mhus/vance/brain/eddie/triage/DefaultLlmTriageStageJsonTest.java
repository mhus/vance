package de.mhus.vance.brain.eddie.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-format tests for the JSON-handling helpers on
 * {@link DefaultLlmTriageStage}. The full chat call is integration
 * territory (real provider, real key) — here we only verify the
 * fence-stripping and parser are tolerant.
 */
class DefaultLlmTriageStageJsonTest {

    @Test
    void stripCodeFences_removesJsonFence() {
        String fenced = "```json\n{\"decision\":\"INBOX\"}\n```";
        assertThat(DefaultLlmTriageStage.stripCodeFences(fenced))
                .isEqualTo("{\"decision\":\"INBOX\"}");
    }

    @Test
    void stripCodeFences_removesPlainFence() {
        String fenced = "```\n{\"decision\":\"VERBATIM\"}\n```";
        assertThat(DefaultLlmTriageStage.stripCodeFences(fenced))
                .isEqualTo("{\"decision\":\"VERBATIM\"}");
    }

    @Test
    void stripCodeFences_passesThroughUnfenced() {
        String raw = "{\"decision\":\"REFORMULATE\"}";
        assertThat(DefaultLlmTriageStage.stripCodeFences(raw)).isEqualTo(raw);
    }

    @Test
    void stripCodeFences_handlesUnclosedFence() {
        // Half-baked LLM output — opens a fence but never closes it.
        // We don't pretend to recover the JSON; we just don't crash.
        String halfFenced = "```json\n{\"decision\":\"INBOX\"}";
        String stripped = DefaultLlmTriageStage.stripCodeFences(halfFenced);
        // Removed the fence header, kept the body.
        assertThat(stripped).contains("\"decision\":\"INBOX\"");
    }
}
