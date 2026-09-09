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
        // GLM-5.x shares the generic tool-argument normalizer: the model
        // emits structurally-invalid JSON args that cortecs' streaming
        // deserializer rejects on replay.
        assertThat(quirks.messageParserFor("glm-5.2")).contains("deepseek-v4");
        assertThat(quirks.messageParserFor("glm-4.6")).contains("deepseek-v4");
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
    void bundledRules_mapReasoningFamiliesToMaxCompletionTokens() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.outputTokenParamFor("gpt-5.6-sol")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
        assertThat(quirks.outputTokenParamFor("gpt-5-mini")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
        assertThat(quirks.outputTokenParamFor("o3")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
        assertThat(quirks.outputTokenParamFor("o4-mini")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
    }

    @Test
    void olderOpenAiWireModels_keepDefaultOutputTokenParam() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        // Nothing matches → caller keeps its own default (max_tokens).
        assertThat(quirks.outputTokenParamFor("gpt-4o")).isEmpty();
        assertThat(quirks.outputTokenParamFor("glm-5.2")).isEmpty();
        assertThat(quirks.outputTokenParamFor("gpt-oss-120b")).isEmpty();
    }

    @Test
    void bundledRules_dropTheSamplingKnobsReasoningModelsReject() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.unsupportedParamsFor("gpt-5.6-sol"))
                .contains(java.util.Set.of(
                        SamplingParam.TEMPERATURE,
                        SamplingParam.TOP_P,
                        SamplingParam.FREQUENCY_PENALTY,
                        SamplingParam.PRESENCE_PENALTY,
                        SamplingParam.STOP_SEQUENCES));
        // seed and the output cap survive — verified against the live
        // model, so the list stays narrow rather than "all sampling".
        assertThat(quirks.unsupportedParamsFor("gpt-5.6-sol"))
                .get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
                .doesNotContain(SamplingParam.SEED);
        assertThat(quirks.unsupportedParamsFor("gpt-4o")).isEmpty();
    }

    @Test
    void bundledRules_forceExplicitReasoningOff_forGpt5Only() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.reasoningEffortWhenOffFor("gpt-5.6-sol")).contains("none");
        // The o-series predates the 'none' value — sending it would be
        // the very error this field exists to prevent.
        assertThat(quirks.reasoningEffortWhenOffFor("o3")).isEmpty();
        assertThat(quirks.reasoningEffortWhenOffFor("gpt-4o")).isEmpty();
    }

    @Test
    void fieldsResolveIndependently_soAParserRuleDoesNotShadowATokenRule() {
        String yaml = """
                rules:
                  - match: "foo-*"
                    messageParser: "parser"
                  - match: "foo-bar"
                    outputTokenParam: "max_completion_tokens"
                  - match: "foo-baz"
                    fimTemplate: "<p>{prefix}<s>{suffix}<m>"
                """;
        ModelQuirks quirks = new ModelQuirks(asResource(yaml));
        assertThat(quirks.messageParserFor("foo-bar")).contains("parser");
        assertThat(quirks.outputTokenParamFor("foo-bar")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
        // Per-field resolution extends to fimTemplate: a rule earlier in
        // the file that doesn't carry the field must not shadow it.
        assertThat(quirks.fimTemplateFor("foo-baz")).contains("<p>{prefix}<s>{suffix}<m>");
    }

    @Test
    void unknownOutputTokenParamValue_dropsTheField_ruleSkippedWhenItCarriesNothingElse() {
        String yaml = """
                rules:
                  - match: "foo-*"
                    outputTokenParam: "max_thingies"
                  - match: "bar-*"
                    outputTokenParam: "max_completion_tokens"
                """;
        ModelQuirks quirks = new ModelQuirks(asResource(yaml));
        assertThat(quirks.ruleCount()).isEqualTo(1);
        assertThat(quirks.outputTokenParamFor("foo-1")).isEmpty();
        assertThat(quirks.outputTokenParamFor("bar-1")).contains(OutputTokenParam.MAX_COMPLETION_TOKENS);
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

    @Test
    void bundledRules_mapFimFamiliesToTheirTokenShape() {
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.fimTemplateFor("qwen2.5-coder-32b-instruct"))
                .contains("<fim_prefix>{prefix}<fim_suffix>{suffix}<fim_middle>");
        assertThat(quirks.fimTemplateFor("Qwen3-Coder-30B-A3B"))
                .contains("<fim_prefix>{prefix}<fim_suffix>{suffix}<fim_middle>");
        assertThat(quirks.fimTemplateFor("deepseek-coder-v2-lite"))
                .contains("<|fim▁begin|>{prefix}<|fim▁hole|>{suffix}<|fim▁end|>");
        assertThat(quirks.fimTemplateFor("codestral-latest")).contains("[PREFIX]{prefix}[SUFFIX]{suffix}[MIDDLE]");
        // Chat models carry no FIM shape — an alias pointing at one must
        // be rejected, not guessed.
        assertThat(quirks.fimTemplateFor("claude-sonnet-4-6")).isEmpty();
        assertThat(quirks.fimTemplateFor("gpt-5.6-sol")).isEmpty();
    }

    @Test
    void bundledFimModels_pickUpTheTemplateFromTheFamilyPattern() {
        // The wire names of the FIM-capable models that actually ship in
        // the bundled catalog (_vance/model/openai/*). None of them sets
        // `fimTemplate` in its per-model YAML — the family pattern is the
        // single source, per-model YAML only when a glob misses (a renamed
        // gateway model, a new family). If one of these ever resolves
        // empty, the follow-up fim picker lost its entry.
        ModelQuirks quirks = new ModelQuirks(new ClassPathResource("vance-defaults/model-quirks.yaml"));
        assertThat(quirks.fimTemplateFor("qwen3-coder-30b-a3b-instruct")).isPresent();
        assertThat(quirks.fimTemplateFor("qwen3-coder-next")).isPresent();
        assertThat(quirks.fimTemplateFor("codestral-2508")).isPresent();
    }

    @Test
    void fimTemplate_withoutOrderedMarkers_isIgnored() {
        String yaml = """
                rules:
                  - match: "foo-*"
                    fimTemplate: "<fim>{suffix}…{prefix}"
                  - match: "bar-*"
                    fimTemplate: "no markers at all"
                """;
        ModelQuirks quirks = new ModelQuirks(asResource(yaml));
        assertThat(quirks.fimTemplateFor("foo-1")).isEmpty();
        assertThat(quirks.fimTemplateFor("bar-1")).isEmpty();
        // Both rules carried only the malformed field → dropped.
        assertThat(quirks.ruleCount()).isZero();
    }

    private static ByteArrayResource asResource(String yaml) {
        return new ByteArrayResource(yaml.getBytes(), "inline-test-yaml");
    }
}
