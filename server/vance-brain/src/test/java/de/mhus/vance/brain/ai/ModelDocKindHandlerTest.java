package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-model} document kind. The catalog
 * never drops a document — it deep-merges and defaults — so the findings pin
 * the two things an operator can actually get wrong: a body that is not a
 * mapping at all (an ERROR, the catalog skips it with WARN) and typed fields
 * that would silently become defaults (hints). The provider sidecar's missing
 * {@code wireType} is the one hard ERROR: without it the instance resolves
 * nothing.
 */
class ModelDocKindHandlerTest {

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public @Nullable String kindOf(String path) {
            return null;
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private final ModelDocKindHandler handler = new ModelDocKindHandler();

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheModelCatalogTree() {
        assertThat(handler.detectsPath("_vance/model/cortecs/deepseek-chat-v3.1.yaml"))
                .isTrue();
        assertThat(handler.detectsPath("_vance/model/ollama/_provider.yaml")).isTrue();
        assertThat(handler.detectsPath("_vance/model/openrouter/google/gemini-3-pro.yaml"))
                .isTrue();
    }

    @Test
    void detectsPath_neverClaimsTheDiscoveryTreeOrNeighbours() {
        // Discovery-owned: written by ModelDiscoveryService, never by hand —
        // typing a write there would invite hand edits.
        assertThat(handler.detectsPath("_vance/model-auto/ollama/qwen3.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/model-old/x.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/config/feeds/usgs.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/model")).isFalse();
    }

    @Test
    void getName_isVanceModel() {
        assertThat(handler.getName()).isEqualTo("vance-model");
    }

    @Test
    void validate_validModelBody_hasNoFindings() {
        String yaml = """
                $meta:
                  kind: vance-model
                contextWindowTokens: 164000
                defaultMaxOutputTokens: 32768
                size: LARGE
                capabilities:
                - THINKING
                pricing:
                  currency: USD
                  inputPerMTok: 0.27
                  outputPerMTok: 1.1
                """;

        assertThat(handler.validate(yaml, ctx("_vance/model/cortecs/deepseek.yaml")))
                .isEmpty();
    }

    @Test
    void validate_emptyOverrideDocument_isLegal() {
        // The catalog deep-merges per field — an empty override inherits
        // everything, so this is not an error.
        assertThat(handler.validate("", ctx("_vance/model/cortecs/override.yaml")))
                .isEmpty();
    }

    @Test
    void validate_brokenYaml_isOneParseError() {
        var findings = handler.validate("contextWindowTokens: [\n", ctx("broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("model-doc-parse");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void validate_nonMappingBody_isAnError() {
        var findings = handler.validate("- a\n- b\n", ctx("list.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("model-doc-mapping"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_numericFieldThatIsNotANumber_isAHint() {
        var findings = handler.validate("contextWindowTokens: \"164000\"\n", ctx("_vance/model/c/t.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("model-doc-number"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_unknownSizeAndCapability_areHints() {
        var findings = handler.validate("size: HUGE\ncapabilities:\n- TELEPATHY\n", ctx("_vance/model/c/t.yaml"));

        assertThat(findings).extracting(Finding::code).contains("model-doc-size", "model-doc-capability-unknown");
        assertThat(findings).allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_lowercaseShippedSpellings_areAccepted() {
        // The shipped catalog documents write capabilities in lowercase
        // (vision, pdf) — the catalog parses case-tolerantly via
        // ModelCapability.fromString, and so must the validation.
        String yaml = """
                capabilities:
                - vision
                - pdf
                size: large
                """;

        assertThat(handler.validate(yaml, ctx("_vance/model/gemini/gemini-2.5-flash.yaml")))
                .isEmpty();
    }

    @Test
    void validate_providerSidecarWithoutWireType_isAnError() {
        var findings = handler.validate("displayName: My Gateway\n", ctx("_vance/model/mygw/_provider.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("model-provider-wire-type"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_providerSidecarWithWireType_hasNoFindings() {
        String yaml = """
                displayName: My Gateway
                wireType: openai
                authType: api-key
                """;

        assertThat(handler.validate(yaml, ctx("_vance/model/mygw/_provider.yaml")))
                .isEmpty();
    }

    @Test
    void validate_modelShapeInProviderFile_isNotAProviderError() {
        // A provider filename is what selects the provider rule — a model
        // body in a model file never trips the wireType check.
        var findings = handler.validate("displayName: x\n", ctx("_vance/model/mygw/some-model.yaml"));

        assertThat(findings).isEmpty();
    }
}
