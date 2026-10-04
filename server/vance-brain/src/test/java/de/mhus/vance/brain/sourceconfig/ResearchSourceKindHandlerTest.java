package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.research.SearchProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-research-source} document kind. The
 * findings mirror what the dispatcher does to a source it cannot use
 * ({@code SearchProviderFactory} drops it): a missing or unknown protocol is
 * an ERROR, a missing endpoint and an undeclared credential are hints. The
 * parser is the canonical {@link SourceConfigLoader#parse}, so these tests pin
 * the same acceptance the runtime has.
 */
class ResearchSourceKindHandlerTest {

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

    private final ResearchSourceKindHandler handler = new ResearchSourceKindHandler(
            new SourceConfigLoader(mock(DocumentService.class)), protocols("serper", "wikipedia", "ode"));

    private static List<SearchProtocol> protocols(String... ids) {
        List<SearchProtocol> out = new ArrayList<>();
        for (String id : ids) {
            SearchProtocol protocol = mock(SearchProtocol.class);
            when(protocol.id()).thenReturn(id);
            out.add(protocol);
        }
        return out;
    }

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void getName_isVanceResearchSource() {
        assertThat(handler.getName()).isEqualTo("vance-research-source");
    }

    @Test
    void validate_validBody_hasNoFindings() {
        String yaml = """
                $meta:
                  kind: vance-research-source
                protocol: serper
                baseUrl: https://google.serper.dev
                apiKey: "{noop}sk-123"
                enabled: true
                """;

        assertThat(handler.validate(yaml, ctx("_vance/config/research/serper-main.yaml")))
                .isEmpty();
    }

    @Test
    void validate_metaHeader_doesNotAffectValidation() {
        assertThat(handler.validate(
                        "protocol: serper\nbaseUrl: https://google.serper.dev\n",
                        ctx("_vance/config/research/serper-main.yaml")))
                .isEmpty();
    }

    @Test
    void validate_brokenYaml_isOneParseError() {
        List<Finding> findings = handler.validate("protocol: [\n", ctx("broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("research-source-parse");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void validate_missingProtocol_isAnError() {
        List<Finding> findings = handler.validate("baseUrl: https://x.test\n", ctx("no-proto.yaml"));

        assertThat(findings).extracting(Finding::code).contains("research-source-protocol");
        assertThat(findings)
                .filteredOn(f -> f.code().equals("research-source-protocol"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_unknownProtocol_isAnErrorListingWhatThisDeploymentServes() {
        List<Finding> findings = handler.validate("protocol: my-own-thing\nbaseUrl: https://x.test\n", ctx("odd.yaml"));

        assertThat(findings).extracting(Finding::code).contains("research-source-protocol-unknown");
        assertThat(findings.get(0).message()).contains("my-own-thing").contains("serper");
    }

    @Test
    void validate_missingBaseUrl_isAWarning() {
        List<Finding> findings = handler.validate("protocol: serper\n", ctx("no-url.yaml"));

        assertThat(findings).extracting(Finding::code).contains("research-source-base-url");
        assertThat(findings).allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_undeclaredCredential_isAWarning() {
        List<Finding> findings =
                handler.validate("protocol: serper\nbaseUrl: https://x.test\napiKey: sk-raw\n", ctx("raw.yaml"));

        assertThat(findings).extracting(Finding::code).contains("research-source-credential-undeclared");
        assertThat(findings).allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_declaredCredentialForms_areSilent() {
        String reference = """
                protocol: serper
                baseUrl: https://x.test
                apiKey: "{{secret:vault:research.serper}}"
                """;
        String literal = """
                protocol: serper
                baseUrl: https://x.test
                apiKey: "{noop}sk-123"
                """;

        assertThat(handler.validate(reference, ctx("ref.yaml"))).isEmpty();
        assertThat(handler.validate(literal, ctx("lit.yaml"))).isEmpty();
    }
}
