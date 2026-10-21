package de.mhus.vance.shared.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.util.PathGlob;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The matcher is the contract between the brain's deny-list filter
 * ({@code planning/user-backup.md} §6.3) and the {@code vance-liver}
 * client's filter — a divergence is a silent failure where one side
 * excludes a document the other includes. The tests pin the exact
 * semantics the planning doc states (Ant-style, project-relative) so
 * that a future "small cleanup" cannot turn into a mismatch.
 */
class PathGlobTest {

    // ── compile() guards ────────────────────────────────────────────

    @Test
    void emptyOrBlankPattern_isRejected() {
        assertThatThrownBy(() -> PathGlob.compile(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> PathGlob.compile("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathGlob.compile(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingSlash_isRejected() {
        // A pattern ending in '/' would silently undermatch — drop the slash
        // and the intent is gone. Better to refuse at compile time.
        assertThatThrownBy(() -> PathGlob.compile("_vance/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not end with '/'");
    }

    @Test
    void doubleSlash_isRejected() {
        assertThatThrownBy(() -> PathGlob.compile("_vance//config")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── basic literal match ─────────────────────────────────────────

    @Test
    void literalSegment_matchesExactly() {
        PathGlob g = PathGlob.compile("notes/idee.md");
        assertThat(g.matches("notes/idee.md")).isTrue();
        assertThat(g.matches("notes/other.md")).isFalse();
        assertThat(g.matches("drafts/notes/idee.md")).isFalse();
    }

    // ── single-star (within segment) ────────────────────────────────

    @Test
    void starMatchesAnyStringWithinSegment() {
        PathGlob g = PathGlob.compile("notes/*.md");
        assertThat(g.matches("notes/idee.md")).isTrue();
        assertThat(g.matches("notes/draft.md")).isTrue();
        // A star is single-segment — it does not cross '/'.
        assertThat(g.matches("notes/sub/draft.md")).isFalse();
        // And only within the suffix the literal says (.md).
        assertThat(g.matches("notes/draft.txt")).isFalse();
        assertThat(g.matches("notes/.md")).isTrue(); // empty stem is still *.
    }

    @Test
    void starDoesNotMatchAcrossSegments() {
        PathGlob g = PathGlob.compile("*.md");
        assertThat(g.matches("idee.md")).isTrue();
        assertThat(g.matches("drafts/idee.md")).isFalse();
    }

    // ── double-star (across segments) ───────────────────────────────

    @Test
    void doubleStarMatchesAnyDepth() {
        PathGlob g = PathGlob.compile("**");
        assertThat(g.matches("")).isTrue();
        assertThat(g.matches("anything")).isTrue();
        assertThat(g.matches("a/b/c.md")).isTrue();
        assertThat(g.matches("a/b/c/d/e/f.md")).isTrue();
    }

    @Test
    void doubleStarAnchorsToPrefix() {
        PathGlob g = PathGlob.compile("**/secret.md");
        assertThat(g.matches("secret.md")).isTrue();
        assertThat(g.matches("a/secret.md")).isTrue();
        assertThat(g.matches("a/b/secret.md")).isTrue();
        // Different filename — no.
        assertThat(g.matches("public.md")).isFalse();
        assertThat(g.matches("a/public.md")).isFalse();
    }

    @Test
    void doubleStarAnchorsToSuffix() {
        PathGlob g = PathGlob.compile("_vance/**");
        assertThat(g.matches("_vance/config/foo.yaml")).isTrue();
        assertThat(g.matches("_vance/x")).isTrue();
        // Default deny-list entry — must NOT match regular documents.
        assertThat(g.matches("notes/idee.md")).isFalse();
    }

    @Test
    void doubleStarInMiddle_worksBothSides() {
        PathGlob g = PathGlob.compile("docs/**/drafts/*.md");
        assertThat(g.matches("docs/drafts/x.md")).isTrue();
        assertThat(g.matches("docs/sub/drafts/x.md")).isTrue();
        assertThat(g.matches("docs/sub/sub/drafts/x.md")).isTrue();
        assertThat(g.matches("docs/drafts/x.txt")).isFalse();
        assertThat(g.matches("notes/drafts/x.md")).isFalse();
    }

    // ── leading slash on input is tolerated ─────────────────────────

    @Test
    void leadingSlashOnInput_isIgnored() {
        PathGlob g = PathGlob.compile("notes/idee.md");
        // Some callers pass URIs verbatim, with their leading '/'. The pattern
        // is project-relative; the input slash should not flip a match.
        assertThat(g.matches("/notes/idee.md")).isTrue();
        assertThat(g.matches("notes/idee.md")).isTrue();
    }

    // ── anyMatch helper ─────────────────────────────────────────────

    @Test
    void anyMatch_handlesEmptyList() {
        // Empty deny-list = nothing is denied = nothing matches the deny
        // filter. The caller's interpretation decides what "no match" means.
        assertThat(PathGlob.anyMatch(List.of(), "anything")).isFalse();
    }

    @Test
    void anyMatch_shortCircuitsOnFirstHit() {
        List<PathGlob> globs = PathGlob.compileAll(List.of("_vance/**", "drafts/**"));
        assertThat(PathGlob.anyMatch(globs, "_vance/config/x.yaml")).isTrue();
        assertThat(PathGlob.anyMatch(globs, "drafts/wip.md")).isTrue();
        assertThat(PathGlob.anyMatch(globs, "published/x.md")).isFalse();
    }

    @Test
    void compileAll_rejectsOnAnyFailure() {
        // A typo in one entry must not half-apply — that would be the
        // exact silent drift the shared matcher is supposed to prevent.
        assertThatThrownBy(() -> PathGlob.compileAll(List.of("notes/**", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    // ── question-mark (single character) ────────────────────────────

    @Test
    void questionMark_matchesSingleCharacter() {
        PathGlob g = PathGlob.compile("notes/?.md");
        assertThat(g.matches("notes/a.md")).isTrue();
        assertThat(g.matches("notes/x.md")).isTrue();
        assertThat(g.matches("notes/ab.md")).isFalse();
        assertThat(g.matches("notes/.md")).isFalse(); // exactly one char
    }

    // ── equals / toString ───────────────────────────────────────────

    @Test
    void equalsByPatternString() {
        // Two compilations of the same pattern are interchangeable — they
        // would be the same deny-list entry, and Set semantics matter for
        // dedup. Pin the contract.
        assertThat(PathGlob.compile("_vance/**")).isEqualTo(PathGlob.compile("_vance/**"));
        assertThat(PathGlob.compile("_vance/**")).isNotEqualTo(PathGlob.compile("_vance/*"));
    }

    @Test
    void toString_includesPattern() {
        assertThat(PathGlob.compile("notes/**").toString()).isEqualTo("PathGlob[notes/**]");
    }
}
