package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class ModelQuirksTest {

    @Test
    void bundledRules_resolveKnownFamilies() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.messageParserFor("deepseek-v4-pro")).contains("deepseek-v4");
        assertThat(quirks.messageParserFor("deepseek-v4-7b")).contains("deepseek-v4");
        assertThat(quirks.messageParserFor("gemma-4-26b-a4b-it")).contains("gemma4");
        assertThat(quirks.messageParserFor("gemma-4-31B-it-qat-4bit")).contains("gemma4");
    }

    @Test
    void unknownModel_returnsEmpty() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.messageParserFor("claude-sonnet-4-5")).isEmpty();
        assertThat(quirks.messageParserFor("gpt-5")).isEmpty();
        assertThat(quirks.messageParserFor(null)).isEmpty();
        assertThat(quirks.messageParserFor("")).isEmpty();
    }

    @Test
    void matchingIsCaseInsensitive() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.messageParserFor("DeepSeek-V4-Pro")).contains("deepseek-v4");
        assertThat(quirks.messageParserFor("GEMMA-4-XL")).contains("gemma4");
    }

    @Test
    void firstMatchWins_whenMultipleRulesCouldApply() {
        String yaml = """
                rules:
                  - match: "foo-*"
                    messageParser: "first"
                  - match: "foo-bar"
                    messageParser: "second"
                """;
        ModelQuirks quirks = new ModelQuirks(asResource(yaml));
        assertThat(quirks.messageParserFor("foo-bar")).contains("first");
    }

    @Test
    void missingFile_yieldsEmptyRules() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("does-not-exist.yaml"));
        assertThat(quirks.ruleCount()).isZero();
        assertThat(quirks.messageParserFor("anything")).isEmpty();
    }

    @Test
    void malformedRule_isSkipped_validRulesStillLoad() {
        String yaml = """
                rules:
                  - match: ""
                    messageParser: "blank"
                  - messageParser: "no-match-key"
                  - match: "good-*"
                    messageParser: "good"
                """;
        ModelQuirks quirks = new ModelQuirks(asResource(yaml));
        assertThat(quirks.ruleCount()).isEqualTo(1);
        assertThat(quirks.messageParserFor("good-1")).contains("good");
    }

    private static ByteArrayResource asResource(String yaml) {
        return new ByteArrayResource(yaml.getBytes(), "inline-test-yaml");
    }
}
