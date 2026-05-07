package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderTypeTest {

    @Test
    void wireName_matchesLowercaseConstant() {
        assertThat(ProviderType.ANTHROPIC.wireName()).isEqualTo("anthropic");
        assertThat(ProviderType.GEMINI.wireName()).isEqualTo("gemini");
        assertThat(ProviderType.OPENAI.wireName()).isEqualTo("openai");
        assertThat(ProviderType.AZURE_OPENAI.wireName()).isEqualTo("azure-openai");
    }

    @Test
    void fromWireName_isCaseInsensitive() {
        assertThat(ProviderType.fromWireName("anthropic")).contains(ProviderType.ANTHROPIC);
        assertThat(ProviderType.fromWireName("ANTHROPIC")).contains(ProviderType.ANTHROPIC);
        assertThat(ProviderType.fromWireName("Anthropic")).contains(ProviderType.ANTHROPIC);
    }

    @Test
    void fromWireName_trimsWhitespace() {
        assertThat(ProviderType.fromWireName("  gemini  ")).contains(ProviderType.GEMINI);
    }

    @Test
    void fromWireName_returnsEmptyForUnknown() {
        assertThat(ProviderType.fromWireName("tippfehler")).isEmpty();
        assertThat(ProviderType.fromWireName("")).isEmpty();
        assertThat(ProviderType.fromWireName(null)).isEmpty();
    }

    @Test
    void requireWireName_throwsForUnknown() {
        assertThatThrownBy(() -> ProviderType.requireWireName("tippfehler"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tippfehler");
    }

    @Test
    void requireWireName_returnsTypeForKnown() {
        assertThat(ProviderType.requireWireName("anthropic")).isEqualTo(ProviderType.ANTHROPIC);
    }
}
