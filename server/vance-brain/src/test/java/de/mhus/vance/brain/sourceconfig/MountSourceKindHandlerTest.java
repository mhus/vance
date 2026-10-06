package de.mhus.vance.brain.sourceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-mount-source} document kind. The
 * findings mirror what the mount factory does to a source it cannot use
 * ({@code JaglanSourceFactory} drops it): a missing or unknown protocol and a
 * {@code local} mount without {@code rootDir} are ERRORs, a missing endpoint
 * on a remote protocol and an undeclared credential are hints. The parser is
 * the canonical {@link SourceConfigLoader#parse}, so these tests pin the same
 * acceptance the runtime has.
 */
class MountSourceKindHandlerTest {

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

    private final MountSourceKindHandler handler = new MountSourceKindHandler(
            new SourceConfigLoader(mock(DocumentService.class)), protocols("local", "demo", "ode"));

    private static List<JaglanProtocol> protocols(String... ids) {
        List<JaglanProtocol> out = new ArrayList<>();
        for (String id : ids) {
            JaglanProtocol protocol = mock(JaglanProtocol.class);
            when(protocol.id()).thenReturn(id);
            out.add(protocol);
        }
        return out;
    }

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheMountsConfigTree() {
        assertThat(handler.detectsPath("_vance/config/mounts/obsidian.yaml")).isTrue();
        assertThat(handler.detectsPath("_vance/config/mounts/any-name.yml")).isTrue();
    }

    @Test
    void detectsPath_neverClaimsOutsideTheTree() {
        // The body shape is shared with research and feeds, so only the
        // folder is the marker — and the prefix must not swallow neighbours
        // like 'mounts-old'.
        assertThat(handler.detectsPath("_vance/config/mounts-old/obsidian.yaml"))
                .isFalse();
        assertThat(handler.detectsPath("_vance/config/research/serper.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/config/feeds/usgs.yaml")).isFalse();
        assertThat(handler.detectsPath("notes/mounts.md")).isFalse();
        assertThat(handler.detectsPath("_vance/config/mounts")).isFalse();
    }

    @Test
    void getName_isVanceMountSource() {
        assertThat(handler.getName()).isEqualTo("vance-mount-source");
    }

    @Test
    void validate_validRemoteBody_hasNoFindings() {
        String yaml = """
                $meta:
                  kind: vance-mount-source
                protocol: ode
                baseUrl: https://ode.example.com
                apiKey: "{noop}sk-123"
                enabled: true
                """;

        assertThat(handler.validate(yaml, ctx("_vance/config/mounts/ode-main.yaml")))
                .isEmpty();
    }

    @Test
    void validate_validLocalBody_hasNoFindings() {
        // No baseUrl needed — the local protocol mounts a directory, and a
        // baseUrl hint there would be noise.
        String yaml = """
                protocol: local
                rootDir: /mnt/vault
                writable: false
                """;

        assertThat(handler.validate(yaml, ctx("_vance/config/mounts/vault.yaml")))
                .isEmpty();
    }

    @Test
    void validate_brokenYaml_isOneParseError() {
        List<Finding> findings = handler.validate("protocol: [\n", ctx("broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("mount-source-parse");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void validate_missingProtocol_isAnError() {
        List<Finding> findings = handler.validate("baseUrl: https://x.test\n", ctx("no-proto.yaml"));

        assertThat(findings).extracting(Finding::code).contains("mount-source-protocol");
        assertThat(findings)
                .filteredOn(f -> f.code().equals("mount-source-protocol"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_unknownProtocol_isAnErrorListingWhatThisDeploymentServes() {
        List<Finding> findings =
                handler.validate("protocol: webdav\nbaseUrl: https://x.test\n", ctx("_vance/config/mounts/dav.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("mount-source-protocol-unknown"))
                .allSatisfy(f -> {
                    assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
                    assertThat(f.message()).contains("local");
                });
    }

    @Test
    void validate_localWithoutRootDir_isAnError() {
        List<Finding> findings = handler.validate("protocol: local\n", ctx("no-root.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("mount-source-root-dir"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_missingBaseUrlOnRemoteProtocol_isAHint() {
        List<Finding> findings = handler.validate("protocol: ode\n", ctx("no-base.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("mount-source-base-url"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }
}
