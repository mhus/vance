package de.mhus.vance.brain.tools.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Self-correction path: what the endpoint says about its own limit has to
 * survive into the next turn's budget, and the strictest value wins.
 */
class ObservedToolLimitRegistryTest {

    private static final String BODY =
            "Invalid 'tools': array too long. Expected an array with maximum length 128, "
                    + "but got an array with length 163 instead.";

    private final ObservedToolLimitRegistry registry = new ObservedToolLimitRegistry();

    @Test
    void learnsTheLimitFromTheRejection() {
        assertThat(registry.learnFrom("openai:gpt-5.6-sol", BODY, 163)).hasValue(128);
        assertThat(registry.observedFor("openai:gpt-5.6-sol")).hasValue(128);
    }

    @Test
    void labelLookupIsCaseInsensitive() {
        registry.learnFrom("OpenAI:GPT-5.6-Sol", BODY, 163);

        assertThat(registry.observedFor("openai:gpt-5.6-sol")).hasValue(128);
    }

    @Test
    void keepsTheStricterLimit_whenAGatewayLowersIt() {
        registry.learnFrom("openai:m", BODY, 163);
        registry.learnFrom("openai:m", "array too long, maximum length 64", 100);

        assertThat(registry.observedFor("openai:m")).hasValue(64);
    }

    @Test
    void doesNotRaiseAPreviouslyLearnedLimit() {
        registry.learnFrom("openai:m", "maximum length 64", 100);
        registry.learnFrom("openai:m", BODY, 163);

        assertThat(registry.observedFor("openai:m")).hasValue(64);
    }

    @Test
    void versionChangesOnlyWhenSomethingWasLearned() {
        long before = registry.version();
        registry.learnFrom("openai:m", BODY, 163);
        long afterLearn = registry.version();
        registry.learnFrom("openai:m", BODY, 163);

        assertThat(afterLearn).isGreaterThan(before);
        assertThat(registry.version()).isEqualTo(afterLearn);
    }

    @Test
    void returnsTheEffectiveLimit_whenTheRejectionWouldRaiseIt() {
        registry.learnFrom("openai:m", "maximum length 64", 100);

        // The caller (ResilientStreamingChatModel) turns this into the
        // user-facing "the limit is now known" message, so it has to be the
        // limit actually in force — not the looser one this rejection stated.
        assertThat(registry.learnFrom("openai:m", BODY, 163)).hasValue(64);
    }

    @Test
    void messageWithoutANumber_learnsNothing() {
        assertThat(registry.learnFrom("openai:m", "tools array too long", 163)).isEmpty();
        assertThat(registry.observedFor("openai:m")).isEmpty();
    }

    @Test
    void unknownLabel_hasNoObservation() {
        assertThat(registry.observedFor("anthropic:claude")).isEmpty();
        assertThat(registry.observedFor("")).isEmpty();
    }
}
