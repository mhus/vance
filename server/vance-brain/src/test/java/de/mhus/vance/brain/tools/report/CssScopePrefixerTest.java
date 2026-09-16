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
    /** The doubled form the prefixer emits, so specificity matches a
     *  Vue scoped style (one class + one attribute = 0,2,0). */
    private static final String EXPECTED = SCOPE + SCOPE;

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
        assertThat(CssScopePrefixer.scope("h1 { color: red; }")).isEqualTo(EXPECTED + " h1 { color: red; }");
    }

    @Test
    void scope_class_prefixed() {
        assertThat(CssScopePrefixer.scope(".note { color: red; }")).isEqualTo(EXPECTED + " .note { color: red; }");
    }

    @Test
    void scope_id_prefixed() {
        assertThat(CssScopePrefixer.scope("#title { color: red; }")).isEqualTo(EXPECTED + " #title { color: red; }");
    }

    @Test
    void scope_commaList_eachPrefixed() {
        assertThat(CssScopePrefixer.scope("h1, h2, h3 { color: red; }"))
                .isEqualTo(EXPECTED + " h1, " + EXPECTED + " h2, " + EXPECTED + " h3 { color: red; }");
    }

    @Test
    void scope_descendantCombinator_prefixed() {
        assertThat(CssScopePrefixer.scope("div p { color: red; }")).isEqualTo(EXPECTED + " div p { color: red; }");
    }

    @Test
    void scope_childCombinator_prefixed() {
        assertThat(CssScopePrefixer.scope("ul > li { color: red; }")).isEqualTo(EXPECTED + " ul > li { color: red; }");
    }

    @Test
    void scope_leadingChildCombinator_scopeOnLeft() {
        // "> .note" means "direct child of the scope root" — the scope
        // class goes on the left of the combinator.
        assertThat(CssScopePrefixer.scope("> .note { color: red; }")).isEqualTo(EXPECTED + " > .note { color: red; }");
    }

    @Test
    void scope_pseudoClass_prefixed() {
        assertThat(CssScopePrefixer.scope("a:hover { color: red; }")).isEqualTo(EXPECTED + " a:hover { color: red; }");
    }

    @Test
    void scope_nthChildWithCommaInsideParens_notSplit() {
        // The comma in nth-child(2n+1, 3) is parenthesised — it must not
        // be treated as a selector-list separator.
        assertThat(CssScopePrefixer.scope("li:nth-child(2n+1, 3) { color: red; }"))
                .isEqualTo(EXPECTED + " li:nth-child(2n+1, 3) { color: red; }");
    }

    @Test
    void scope_isPseudoFunctionWithComma_notSplit() {
        assertThat(CssScopePrefixer.scope(":is(h1, h2, h3) { color: red; }"))
                .isEqualTo(EXPECTED + " :is(h1, h2, h3) { color: red; }");
    }

    @Test
    void scope_atMedia_innerRulesPrefixed() {
        String css = "@media screen { h1 { color: red; } .note { color: blue; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
                .contains("@media screen")
                .contains(EXPECTED + " h1 { color: red; }")
                .contains(EXPECTED + " .note { color: blue; }");
    }

    @Test
    void scope_atMediaWithQuery_innerRulesPrefixed() {
        String css = "@media screen and (min-width: 600px) { h1 { color: red; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result).contains("@media screen and (min-width: 600px)").contains(EXPECTED + " h1 { color: red; }");
    }

    @Test
    void scope_atSupports_innerRulesPrefixed() {
        String css = "@supports (display: grid) { .grid { display: grid; } }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result).contains("@supports (display: grid)").contains(EXPECTED + " .grid { display: grid; }");
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
                .contains(EXPECTED + " h1 { color: red; }")
                .contains("@media print { " + EXPECTED + " h2 { color: black; } }")
                .contains("@page { margin: 10mm; }")
                .contains(EXPECTED + " .note { color: blue; }");
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
                .contains(EXPECTED + " h1 { color: red; }")
                .contains(EXPECTED + " h2 { color: blue; }")
                .contains(EXPECTED + " p { margin: 0; }");
    }

    @Test
    void scope_commentBeforeSelector_strippedAndPrefixed() {
        // A comment before a selector would land between the scope
        // prefix and the element if not stripped — ".scope /* x */ h1"
        // breaks the cascade. The comment is metadata, not part of the
        // selector, so we drop it before prefixing.
        String css = "/* Warm accent */\nh1, h2 { color: #8a6d1a; }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result)
                .contains(EXPECTED + " h1, " + EXPECTED + " h2 { color: #8a6d1a; }")
                .doesNotContain("/* Warm accent */");
    }

    @Test
    void scope_commentInsideCommaList_stripped() {
        String css = "h1 /* heading */ , h2 { color: red; }";
        String result = CssScopePrefixer.scope(css);
        assertThat(result).contains(EXPECTED + " h1").contains(EXPECTED + " h2").doesNotContain("/* heading */");
    }

    // ── parameterized scope (chat themes) ──────────────────────────

    @Test
    void scopeWithCustomScopeClass_usedInsteadOfDefault() {
        assertThat(CssScopePrefixer.scope("h1 { color: red; }", ".chat-theme"))
                .isEqualTo(".chat-theme.chat-theme h1 { color: red; }");
    }

    @Test
    void scopeWithCustomScopeClass_atMediaInnerRulesPrefixed() {
        String css = "@media screen { .msg-user { color: red; } }";
        String result = CssScopePrefixer.scope(css, ".chat-theme");
        assertThat(result).contains("@media screen").contains(".chat-theme.chat-theme .msg-user { color: red; }");
    }

    @Test
    void scopeWithCustomScopeClass_commaListEachPrefixed() {
        assertThat(CssScopePrefixer.scope("h1, .msg-ai { color: red; }", ".chat-theme"))
                .isEqualTo(".chat-theme.chat-theme h1, .chat-theme.chat-theme .msg-ai { color: red; }");
    }

    @Test
    void scopeWithCustomScopeClass_nullCss_returnsEmpty() {
        assertThat(CssScopePrefixer.scope(null, ".chat-theme")).isEmpty();
    }

    @Test
    void scopeWithCustomScopeClass_blankScopeClass_rejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> CssScopePrefixer.scope("h1 { }", " "));
    }

    // ── pre-scoped pass-through ────────────────────────────────────

    @Test
    void scope_preScopedSelector_passedThroughUntouched() {
        // The escape hatch for rules the descendant prefix cannot
        // express — most importantly mode selectors on the scope root:
        // the chat dark mode lives on an ancestor element.
        String css = ".chat-theme[data-mode=dark] .msg-user { color: red; }";
        assertThat(CssScopePrefixer.scope(css, ".chat-theme")).isEqualTo(css);
    }

    @Test
    void scope_preScopedSelectorInMixedSheet_plainSelectorsStillPrefixed() {
        String css = ".msg-user { color: red; }\n.chat-theme[data-mode=dark] .msg-user { color: blue; }";
        String result = CssScopePrefixer.scope(css, ".chat-theme");
        assertThat(result)
                .contains(".chat-theme.chat-theme .msg-user { color: red; }")
                .contains(".chat-theme[data-mode=dark] .msg-user { color: blue; }");
    }

    @Test
    void scope_similarButDifferentClass_notTreatedAsPreScoped() {
        // ".chat-theme" must not count as contained in ".chat-theme-dark"
        // — the token check is boundary-aware.
        assertThat(CssScopePrefixer.scope(".chat-theme-dark .x { color: red; }", ".chat-theme"))
                .isEqualTo(".chat-theme.chat-theme .chat-theme-dark .x { color: red; }");
    }

    @Test
    void scope_scopeClassNotAtStart_stillPassedThrough() {
        // Pre-scoped means "contains the scope class", not "starts
        // with it" — a compound selector may lead with something else.
        String css = "body > .chat-theme .x { color: red; }";
        assertThat(CssScopePrefixer.scope(css, ".chat-theme")).isEqualTo(css);
    }
}
