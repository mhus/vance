package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-feed-source} document kind. The
 * findings mirror what the feed factory does to a source it cannot use
 * ({@code FeedSourceFactory} drops it): a missing or unknown protocol is an
 * ERROR, a missing endpoint and an undeclared credential are hints. The
 * parser is the canonical {@link SourceConfigLoader#parse}, so these tests
 * pin the same acceptance the runtime has.
 */
class FeedSourceKindHandlerTest {

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

    private final FeedSourceKindHandler handler = new FeedSourceKindHandler(
            new SourceConfigLoader(mock(DocumentService.class)), protocols("usgs", "wikipedia", "ode"));

    private static List<FeedProtocol> protocols(String... ids) {
        List<FeedProtocol> out = new ArrayList<>();
        for (String id : ids) {
            FeedProtocol protocol = mock(FeedProtocol.class);
            when(protocol.id()).thenReturn(id);
            out.add(protocol);
        }
        return out;
    }

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheFeedsConfigTree() {
        assertThat(handler.detectsPath("_vance/config/feeds/usgs.yaml")).isTrue();
        assertThat(handler.detectsPath("_vance/config/feeds/any-name.yml")).isTrue();
    }

    @Test
    void detectsPath_neverClaimsOutsideTheTree() {
        // The body shape is shared with research and Jaglan mounts, so only
        // the folder is the marker — and the prefix must not swallow
        // neighbours like 'feeds-old'.
        assertThat(handler.detectsPath("_vance/config/feeds-old/usgs.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/config/research/serper.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/config/mounts/jira.yaml")).isFalse();
        assertThat(handler.detectsPath("notes/feeds.md")).isFalse();
        assertThat(handler.detectsPath("_vance/config/feeds")).isFalse();
    }

    @Test
    void getName_isVanceFeedSource() {
        assertThat(handler.getName()).isEqualTo("vance-feed-source");
    }

    @Test
    void validate_validBody_hasNoFindings() {
        String yaml = """
                $meta:
                  kind: vance-feed-source
                protocol: usgs
                baseUrl: https://earthquake.usgs.gov
                enabled: true
                """;

        assertThat(handler.validate(yaml, ctx("_vance/config/feeds/usgs.yaml"))).isEmpty();
    }

    @Test
    void validate_brokenYaml_isOneParseError() {
        List<Finding> findings = handler.validate("protocol: [\n", ctx("broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("feed-source-parse");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void validate_missingProtocol_isAnError() {
        List<Finding> findings = handler.validate("baseUrl: https://x.test\n", ctx("no-proto.yaml"));

        assertThat(findings).extracting(Finding::code).contains("feed-source-protocol");
        assertThat(findings)
                .filteredOn(f -> f.code().equals("feed-source-protocol"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_unknownProtocol_isAnErrorListingWhatThisDeploymentServes() {
        List<Finding> findings = handler.validate(
                "protocol: mastodon\nbaseUrl: https://x.test\n", ctx("_vance/config/feeds/masto.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("feed-source-protocol-unknown"))
                .allSatisfy(f -> {
                    assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
                    assertThat(f.message()).contains("usgs");
                });
    }

    @Test
    void validate_missingBaseUrl_isAHint() {
        List<Finding> findings = handler.validate("protocol: usgs\n", ctx("no-base.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("feed-source-base-url"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void validate_undeclaredCredential_isAHint() {
        List<Finding> findings =
                handler.validate("protocol: usgs\nbaseUrl: https://x.test\napiKey: sk-123\n", ctx("bare-key.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("feed-source-credential-undeclared"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }
}
