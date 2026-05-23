package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.brain.fook.ToolErrorPattern.HealthAction;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolErrorPatternResolverTest {

    private ToolErrorPatternResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ToolErrorPatternResolver(mock(DocumentService.class));
        resolver.reloadBundledForTest();
    }

    @Test
    void bundled_patternsLoadAndExposeKnownRules() {
        // Sanity — the bundled YAML ships at least the canonical rules
        // that the spec table promises.
        List<ToolErrorPattern> bundled = resolver.bundledForTest();
        assertThat(bundled).isNotEmpty();
        assertThat(bundled.stream().map(ToolErrorPattern::getId))
                .contains("http-403", "http-429", "connect-refused", "fallback");
    }

    @Test
    void parseText_http403_classifiesAsUserPermission() {
        String yaml = """
                patterns:
                  - id: http-403
                    match: { httpStatus: 403 }
                    signature: http-403
                    classification: USER_PERMISSION
                    cooldown: PT24H
                    locked: true
                """;
        List<ToolErrorPattern> patterns = resolver.parseText(yaml);
        assertThat(patterns).hasSize(1);
        ToolErrorPattern p = patterns.get(0);
        assertThat(p.getHttpStatus()).isEqualTo(403);
        assertThat(p.getSignature()).isEqualTo("http-403");
        assertThat(p.getClassification()).isEqualTo(ToolHealthClassification.USER_PERMISSION);
        assertThat(p.getCooldown()).isEqualTo(Duration.ofHours(24));
        assertThat(p.isLocked()).isTrue();
    }

    @Test
    void parseText_headerCooldown_isSentinelDuration() {
        String yaml = """
                patterns:
                  - id: http-429
                    match: { httpStatus: 429 }
                    signature: http-429
                    classification: INTERMITTENT
                    cooldown: header:retry-after
                """;
        ToolErrorPattern p = resolver.parseText(yaml).get(0);
        assertThat(p.getCooldown()).isEqualTo(ToolErrorPattern.COOLDOWN_FROM_RETRY_AFTER);
    }

    @Test
    void parseText_healthActionMarkUnavailable_isParsedCaseInsensitively() {
        String yaml = """
                patterns:
                  - id: connect-refused
                    match: { exceptionType: [java.net.ConnectException] }
                    signature: connect-refused
                    classification: TECHNICALLY_BROKEN
                    healthAction: markUnavailable
                """;
        ToolErrorPattern p = resolver.parseText(yaml).get(0);
        assertThat(p.getHealthAction()).isEqualTo(HealthAction.MARK_UNAVAILABLE);
        assertThat(p.getExceptionTypes()).containsExactly("java.net.ConnectException");
    }

    @Test
    void parseText_emptyMatch_yieldsCatchAll() {
        String yaml = """
                patterns:
                  - id: fallback
                    match: {}
                    signature: unclassified
                    classification: UNCLEAR
                """;
        ToolErrorPattern p = resolver.parseText(yaml).get(0);
        assertThat(p.getHttpStatus()).isNull();
        assertThat(p.getHttpStatusRange()).isNull();
        assertThat(p.getExceptionTypes()).isNull();
        assertThat(p.getBodyContains()).isNull();
    }

    @Test
    void merge_tenantReplacesBundledById() {
        ToolErrorPattern bundled = ToolErrorPattern.builder()
                .id("http-403")
                .httpStatus(403)
                .signature("http-403")
                .classification(ToolHealthClassification.USER_PERMISSION)
                .cooldown(Duration.ofHours(24))
                .build();
        ToolErrorPattern bundledFallback = ToolErrorPattern.builder()
                .id("fallback").signature("unclassified")
                .classification(ToolHealthClassification.UNCLEAR).build();

        ToolErrorPattern tenantOverride = ToolErrorPattern.builder()
                .id("http-403")
                .httpStatus(403)
                .signature("http-403")
                .classification(ToolHealthClassification.USER_PERMISSION)
                .cooldown(Duration.ofHours(1))           // shorter override
                .build();

        List<ToolErrorPattern> merged = ToolErrorPatternResolver.merge(
                List.of(bundled, bundledFallback),
                List.of(tenantOverride),
                List.of());

        // Same position in the result, but with the tenant's cooldown.
        assertThat(merged.get(0).getCooldown()).isEqualTo(Duration.ofHours(1));
        assertThat(merged.get(0).getId()).isEqualTo("http-403");
        assertThat(merged).hasSize(2);
    }

    @Test
    void merge_newTenantRuleIsInsertedBeforeFallback() {
        ToolErrorPattern bundledFirst = ToolErrorPattern.builder()
                .id("http-403").signature("http-403")
                .classification(ToolHealthClassification.USER_PERMISSION).build();
        ToolErrorPattern bundledFallback = ToolErrorPattern.builder()
                .id("fallback").signature("unclassified")
                .classification(ToolHealthClassification.UNCLEAR).build();
        ToolErrorPattern tenantNew = ToolErrorPattern.builder()
                .id("custom-422").httpStatus(422)
                .signature("http-422")
                .classification(ToolHealthClassification.USER_INPUT).build();

        List<ToolErrorPattern> merged = ToolErrorPatternResolver.merge(
                List.of(bundledFirst, bundledFallback),
                List.of(tenantNew),
                List.of());

        assertThat(merged).hasSize(3);
        // bundled rule first, tenant new before fallback, fallback last.
        assertThat(merged.get(0).getId()).isEqualTo("http-403");
        assertThat(merged.get(1).getId()).isEqualTo("custom-422");
        assertThat(merged.get(2).getId()).isEqualTo("fallback");
    }

    @Test
    void merge_projectLayerWinsOverTenantById() {
        ToolErrorPattern bundled = ToolErrorPattern.builder()
                .id("http-403").signature("http-403")
                .classification(ToolHealthClassification.USER_PERMISSION)
                .cooldown(Duration.ofHours(24)).build();
        ToolErrorPattern tenant = bundled.toBuilder().cooldown(Duration.ofHours(12)).build();
        ToolErrorPattern project = bundled.toBuilder().cooldown(Duration.ofMinutes(15)).build();

        List<ToolErrorPattern> merged = ToolErrorPatternResolver.merge(
                List.of(bundled), List.of(tenant), List.of(project));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getCooldown()).isEqualTo(Duration.ofMinutes(15));
    }
}
