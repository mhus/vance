package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.KindRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KindResolver}. Verifies the resolution ladder
 * (blank → existing → exact → substring → fallback) with a focus on
 * the {@code formula} kind: the resolver must recognise {@code formula},
 * {@code Formula}, and substring variants like {@code "formula-block"}
 * without coercing them to {@code text}.
 */
class KindResolverTest {

    private static final Set<String> KNOWN_KINDS = Set.of(
            "text",
            "slides",
            "schema",
            "application",
            "compose",
            "formula",
            "diagram",
            "mindmap",
            "chart",
            "graph",
            "records",
            "sheet",
            "list",
            "checklist",
            "tree",
            "data");

    private KindRegistry registryWith(Set<String> names) {
        KindRegistry registry = mock(KindRegistry.class);
        when(registry.isKnown(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> names.contains(inv.<String>getArgument(0).toLowerCase()));
        when(registry.names()).thenReturn(names);
        return registry;
    }

    private final KindResolver resolver = new KindResolver(registryWith(KNOWN_KINDS));

    // ── path-based defaults ─────────────────────────────────────

    private KindResolver resolverWithPathClaim(String claim) {
        KindRegistry registry = registryWith(KNOWN_KINDS);
        when(registry.detectKind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(claim);
        return new KindResolver(registry);
    }

    @Test
    void pathClaim_typesAnUnkindedCreate() {
        KindResolver r = resolverWithPathClaim("vance-research-source");
        assertThat(r.resolve(null, null, "protocol: serper", "_vance/config/research/serper.yaml"))
                .isEqualTo("vance-research-source");
    }

    @Test
    void pathClaim_losesAgainstAnExplicitKind() {
        // kind= is a decision — the location never re-types what the caller
        // asked for.
        KindResolver r = resolverWithPathClaim("vance-research-source");
        assertThat(r.resolve("diagram", null, "x", "_vance/config/research/serper.yaml"))
                .isEqualTo("diagram");
    }

    @Test
    void pathClaim_losesAgainstAnExistingKind() {
        KindResolver r = resolverWithPathClaim("vance-research-source");
        assertThat(r.resolve(null, "sheet", "x", "_vance/config/research/serper.yaml"))
                .isEqualTo("sheet");
    }

    @Test
    void pathClaim_replacesAnUncommittedTextKind() {
        // 'text' is the name of 'unspecified', not a commitment — an update
        // that leaves kind blank re-types the document by location.
        KindResolver r = resolverWithPathClaim("vance-research-source");
        assertThat(r.resolve(null, "text", "x", "_vance/config/research/serper.yaml"))
                .isEqualTo("vance-research-source");
    }

    // ── a requested `text` loses against the location, not the body ──

    private static final String RESEARCH_PATH = "_vance/config/research/serper.yaml";

    private KindResolver resolverClaimingPathOnly(String path, String claim) {
        KindRegistry registry = registryWith(KNOWN_KINDS);
        // The body would claim `diagram` — a requested text must never ask it.
        when(registry.detectKind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("diagram");
        when(registry.detectKind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(null);
        when(registry.detectKind(org.mockito.ArgumentMatchers.eq(path), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(claim);
        return new KindResolver(registry);
    }

    @Test
    void requestedText_atAClaimedPath_takesThePathKind() {
        // A text-typed write into the config tree would skip the source
        // validation silently — the location is what the document is.
        KindResolver r = resolverClaimingPathOnly(RESEARCH_PATH, "vance-research-source");
        assertThat(r.resolve("text", null, "protocol: serper", RESEARCH_PATH)).isEqualTo("vance-research-source");
        assertThat(r.resolve("Text", null, "protocol: serper", RESEARCH_PATH)).isEqualTo("vance-research-source");
    }

    @Test
    void requestedText_overwritingASourceAtItsPath_doesNotDowngradeIt() {
        KindResolver r = resolverClaimingPathOnly(RESEARCH_PATH, "vance-research-source");
        assertThat(r.resolve("text", "vance-research-source", "protocol: serper", RESEARCH_PATH))
                .isEqualTo("vance-research-source");
    }

    @Test
    void requestedText_atAnUnclaimedPath_staysText_evenWithADetectableBody() {
        // Issue #70 stays fixed: only the location may override a requested text.
        KindResolver r = resolverClaimingPathOnly(RESEARCH_PATH, "vance-research-source");
        assertThat(r.resolve("text", null, "Intro\n```mermaid\nflowchart TD\n```", "notes/intro.md"))
                .isEqualTo("text");
    }

    // ── formula-specific cases ──────────────────────────────────

    @Test
    void formula_exactMatch_resolvesToFormula() {
        assertThat(resolver.resolve("formula", null)).isEqualTo("formula");
    }

    @Test
    void formula_caseInsensitive_resolvesToFormula() {
        assertThat(resolver.resolve("Formula", null)).isEqualTo("formula");
        assertThat(resolver.resolve("FORMULA", null)).isEqualTo("formula");
    }

    @Test
    void formula_substringVariant_resolvesToFormula() {
        // "formula-block" contains "formula" → resolves to formula
        assertThat(resolver.resolve("formula-block", null)).isEqualTo("formula");
    }

    @Test
    void formula_onOverwrite_keepsFormulaWhenBlank() {
        // Blank kind on overwrite keeps the existing kind
        assertThat(resolver.resolve(null, "formula")).isEqualTo("formula");
        assertThat(resolver.resolve("", "formula")).isEqualTo("formula");
        assertThat(resolver.resolve("  ", "formula")).isEqualTo("formula");
    }

    // ── general resolution ladder ──────────────────────────────

    @Test
    void blankRequest_onCreate_defaultsToText() {
        assertThat(resolver.resolve(null, null)).isEqualTo("text");
        assertThat(resolver.resolve("", null)).isEqualTo("text");
    }

    @Test
    void blankRequest_onOverwrite_keepsExisting() {
        assertThat(resolver.resolve(null, "diagram")).isEqualTo("diagram");
    }

    @Test
    void typoContainingKnownKind_resolvesViaSubstring() {
        // "diagramm" contains "diagram" → resolves to diagram
        assertThat(resolver.resolve("diagramm", null)).isEqualTo("diagram");
    }

    @Test
    void unresolvableKind_onCreate_fallsBackToText() {
        assertThat(resolver.resolve("frobnicate", null)).isEqualTo("text");
    }

    @Test
    void unresolvableKind_onOverwrite_keepsExisting() {
        assertThat(resolver.resolve("frobnicate", "slides")).isEqualTo("slides");
    }

    // ── a requested `text` is a decision ────────────────────────
    //
    // Detection only runs when no kind is requested. From d1af5ea62 until
    // GitHub issue #70 a requested `text` counted as "unspecified" — see the
    // history comment in KindResolver before loosening this again. An
    // existing `text` stays unspecific: it is also the silent create fallback.

    private KindResolver resolverDetecting(String detected) {
        KindRegistry registry = registryWith(KNOWN_KINDS);
        when(registry.detectKind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(detected);
        return new KindResolver(registry);
    }

    @Test
    void requestedText_withDetectableBody_staysText() {
        // Issue #70: prose with an embedded mermaid block, written as text.
        assertThat(resolverDetecting("diagram").resolve("text", null, "Intro\n```mermaid\nflowchart TD\n```"))
                .isEqualTo("text");
    }

    @Test
    void requestedText_overwritingText_isNotReDetected() {
        assertThat(resolverDetecting("diagram").resolve("text", "text", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("text");
    }

    @Test
    void blankRequest_withDetectableBody_yieldsDetectedKind() {
        assertThat(resolverDetecting("diagram").resolve(null, null, "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }

    @Test
    void requestedText_withUndetectableBody_staysText() {
        assertThat(resolverDetecting(null).resolve("text", null, "just prose")).isEqualTo("text");
    }

    @Test
    void requestedSpecificKind_isNeverOverriddenByDetection() {
        // The caller committed to chart; a diagram detector must not win.
        assertThat(resolverDetecting("diagram").resolve("chart", null, "```mermaid\npie\n```"))
                .isEqualTo("chart");
    }

    @Test
    void textRequest_retypesAnExistingSpecificKind() {
        // An explicit kind change is allowed — text included.
        assertThat(resolverDetecting("mindmap").resolve("text", "diagram", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("text");
    }

    @Test
    void existingSpecificKind_survivesABlankRequest() {
        assertThat(resolverDetecting("mindmap").resolve(null, "diagram", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }

    @Test
    void existingText_isReplacedByDetection() {
        // An existing `text` is as unspecific as a requested one.
        assertThat(resolverDetecting("diagram").resolve(null, "text", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }
}
