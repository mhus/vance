package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pattern semantics of the model allowlist — the same matcher backs
 * Wowbagger's fail-closed gate and Trillian's relaxed one (D8): the gate
 * quality comes from the allowlist value, not from the code.
 */
class ModelAllowlistTest {

    @Test
    void blankAllowlistMeansDeny() {
        assertThat(ModelAllowlist.approved("default:fast", null)).isFalse();
        assertThat(ModelAllowlist.approved("default:fast", "")).isFalse();
        assertThat(ModelAllowlist.approved("default:fast", "  ")).isFalse();
    }

    @Test
    void starAllowsEverything() {
        // Trillian's relaxed default.
        assertThat(ModelAllowlist.approved("default:fast", "*")).isTrue();
        assertThat(ModelAllowlist.approved("oai:gpt-5", "*")).isTrue();
    }

    @Test
    void matchesOnTheResolvedModelOrItsBareName() {
        assertThat(ModelAllowlist.approved("oai:gpt-5", "oai:gpt-5")).isTrue();
        assertThat(ModelAllowlist.approved("oai:gpt-5", "oai:gpt-4")).isFalse();
        // the bare name (behind the provider) matches just as well
        assertThat(ModelAllowlist.approved("oai:gpt-5:analyze", "gpt-5:analyze"))
                .isTrue();
        // but a different task qualifier is a different resolved model
        assertThat(ModelAllowlist.approved("oai:gpt-5:fast", "oai:gpt-5:analyze"))
                .isFalse();
    }

    @Test
    void commaListsAreAlternatives() {
        assertThat(ModelAllowlist.approved("default:fast", "oai:gpt-5,default:fast"))
                .isTrue();
        assertThat(ModelAllowlist.approved("default:slow", "oai:gpt-5,default:fast"))
                .isFalse();
    }

    @Test
    void wildcardsCoverAProviderFamily() {
        assertThat(ModelAllowlist.approved("oai:gpt-5:whatever", "gpt-5*")).isTrue();
        assertThat(ModelAllowlist.approved("oai:gpt-5:whatever", "oai:*")).isTrue();
        assertThat(ModelAllowlist.approved("default:fast", "oai:*")).isFalse();
    }
}
