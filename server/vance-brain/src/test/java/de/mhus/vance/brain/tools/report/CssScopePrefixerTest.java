package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CssScopePrefixer} — every selector gets the
 * {@code .markdown-document-preview} prefix so a theme cannot leak onto
 * the Cortex shell. Each test pins one shape: plain rules, comma lists,
 * combinators, at-rules, and the preserves ({@code @page}/@font-face}/
 * {@code @keyframes} which must not be prefixed).
 */
class CssScopePrefixerTest {

    private static final String SCOPE = CssScopePrefixer.SCOPE;

    @Test
    void scope_null_returnsEmpty() {
        assertThat(CssScopePrefixer.scope(null)).isEmpty();
    }

    @Test
    void scope_empty_returnsEmpty() {
        assertThat(CssScopePrefixer.scope("")).isEmpty();
    }

    @Test
    void scope_plainElement_prefixed() {
        assertThat(CssScopePrefixer.scope("h1 { color: red; }"))
            .isEqualTo(SCOPE + " h1 { color: red; }");
    }

    @Test
    void scope_class_prefixed() {
        assertThat(CssScopePrefixer.scope(".note { color: red; }"))
            .isEqualTo(SCOPE + " .note { color: red; }");
    }

    @Test
    void scope_id_prefixed() {
        assertThat(CssScopePrefixer.scope("#title { color: red; }"))
            .isEqualTo(SCOPE + " #title { color: red; }");
    }

    @Test
    void scope_commaList_eachPrefixed() {
        assertThat(CssScopePrefixer.scope("h1, h2, h3 { color: red; }"))
            .isEqualTo(SCOPE + " h1, " + SCOPE + " h2, " + SCOPE + " h3 { color: red; }");
    }

    @Test
    void scope_descendantCombinator_prefixed() {
        assertThat(CssScopePrefixer.scope("div p { color: red; }"))
            .isEqualTo(SCOPE + " div p { color: red; }");
    }

    @Test
    void scope_childCombinator_prefixed() {
        assertThat(CssScopePrefixer.scope("ul > li { color: red; }"))
            .isEqualTo(SCOPE + " ul > li { color: red; }");
    }

    @Test
    void scope_leadingChildCombinator_scopeOnLeft() {
        // "> .note" means "direct child of the scope root" — the scope
        // class goes on the left of the combinator.
        assertThat(CssScopePrefixer.scope("> .note { color: red; }"))
            .isEqualTo(SCOPE + " > .note { color: red; }");
    }

    @Test
    void scope_pseudoClass_prefixed() {
        assertThat(CssScopePrefixer.scope("a:hover { color: red; }"))
            .isEqualTo(SCOPE + " a:hover { color: red; }");
    }

    @Test
    void scope_nthChildWithCommaInsideParens_notSplit() {
        // The comma in nth-child(2n+1, 3) is parenthesised — it must not
        // be treated as a selector-list separator.
        assertThat(CssScopePrefixer.scope("li:nth-child(2n+1, 3) { color: red; }"))
            .isEqualTo(SCOPE + " li:nth-child(2n+1, 3) { color: red; }");
    }

    @Test
    void scope_isPseudoFunctionWithComma_notSplit() {
        assertThat(CssScopePrefixer.scope(":is(h1, h2, h3) { color: red; }"))
            .isEqualTo(SCOPE + " :is(h1, h2, h3) { color: red; }");
    }

    @Test
    void scope_atMedia_innerRulesPrefixed() {
        String css = "@media screen { h1 { color: red; } .note { color: blue; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
            .contains("@media screen")
            .contains(SCOPE + " h1 { color: red; }")
            .contains(SCOPE + " .note { color: blue; }");
    }

    @Test
    void scope_atMediaWithQuery_innerRulesPrefixed() {
        String css = "@media screen and (min-width: 600px) { h1 { color: red; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
            .contains("@media screen and (min-width: 600px)")
            .contains(SCOPE + " h1 { color: red; }");
    }

    @Test
    void scope_atSupports_innerRulesPrefixed() {
        String css = "@supports (display: grid) { .grid { display: grid; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
            .contains("@supports (display: grid)")
            .contains(SCOPE + " .grid { display: grid; }");
    }

    @Test
    void scope_atPage_preservedUnchanged() {
        String css = "@page { margin: 15mm; @bottom-right { content: counter(page); } }";
        assertThat(CssScopePrefixer.scope(css)).isEqualTo(css);
    }

    @Test
    void scope_atFontFace_preservedUnchanged() {
        String css = "@font-face { font-family: 'X'; src: url(data:font/woff2;base64,abc=); }";
        assertThat(CssScopePrefixer.scope(css)).isEqualTo(css);
    }

    @Test
    void scope_atKeyframes_preservedUnchanged() {
        String css = "@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }";
        assertThat(CssScopePrefixer.scope(css)).isEqualTo(css);
    }

    @Test
    void scope_mixedRulesAndAtRules_correctSplit() {
        String css = "h1 { color: red; }\n"
            + "@media print { h2 { color: black; } }\n"
            + "@page { margin: 10mm; }\n"
            + ".note { color: blue; }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
            .contains(SCOPE + " h1 { color: red; }")
            .contains("@media print { " + SCOPE + " h2 { color: black; } }")
            .contains("@page { margin: 10mm; }")
            .contains(SCOPE + " .note { color: blue; }");
    }

    @Test
    void scope_emptySelectorRule_preservedAsIs() {
        // A rule with only whitespace as selector (e.g. after comment strip)
        // should not produce a stray ".scope { … }".
        String css = "{ color: red; }";
        // The rule matcher treats the whole thing as one rule with an
        // empty selector — we pass it through unchanged.
        assertThat(CssScopePrefixer.scope(css)).isEqualTo(css);
    }

    @Test
    void scope_multipleRules_allPrefixed() {
        String css = "h1 { color: red; } h2 { color: blue; } p { margin: 0; }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
            .contains(SCOPE + " h1 { color: red; }")
            .contains(SCOPE + " h2 { color: blue; }")
            .contains(SCOPE + " p { margin: 0; }");
    }
}
