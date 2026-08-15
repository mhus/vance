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
            "text", "slides", "schema", "application", "compose", "formula",
            "diagram", "mindmap", "chart", "graph", "records", "sheet",
            "list", "checklist", "tree", "data");

    private KindRegistry registryWith(Set<String> names) {
        KindRegistry registry = mock(KindRegistry.class);
        when(registry.isKnown(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> names.contains(inv.<String>getArgument(0).toLowerCase()));
        when(registry.names()).thenReturn(names);
        return registry;
    }

    private final KindResolver resolver = new KindResolver(registryWith(KNOWN_KINDS));

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

    // ── `text` is not a commitment ──────────────────────────────
    //
    // It is the name of "unspecified" and what a model picks when it does
    // not decide: of 33 doc_write calls in one MermaidVariety run, 30 passed
    // an explicit kind and every mis-typed diagram carried kind=text beside a
    // ```mermaid body. So `text` lets the registered kinds claim the content;
    // a specific kind never does.

    private KindResolver resolverDetecting(String detected) {
        KindRegistry registry = registryWith(KNOWN_KINDS);
        when(registry.detectKind(org.mockito.ArgumentMatchers.any()))
                .thenReturn(detected);
        return new KindResolver(registry);
    }

    @Test
    void requestedText_withDetectableBody_yieldsDetectedKind() {
        assertThat(resolverDetecting("diagram")
                .resolve("text", null, "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }

    @Test
    void requestedText_withUndetectableBody_staysText() {
        assertThat(resolverDetecting(null).resolve("text", null, "just prose"))
                .isEqualTo("text");
    }

    @Test
    void requestedSpecificKind_isNeverOverriddenByDetection() {
        // The caller committed to chart; a diagram detector must not win.
        assertThat(resolverDetecting("diagram")
                .resolve("chart", null, "```mermaid\npie\n```"))
                .isEqualTo("chart");
    }

    @Test
    void existingSpecificKind_survivesATextRequest() {
        // Overwriting a diagram with kind=text must not retype the document.
        assertThat(resolverDetecting("mindmap")
                .resolve("text", "diagram", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }

    @Test
    void existingText_isReplacedByDetection() {
        // An existing `text` is as unspecific as a requested one.
        assertThat(resolverDetecting("diagram")
                .resolve(null, "text", "```mermaid\nflowchart TD\n```"))
                .isEqualTo("diagram");
    }
}
