package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit coverage of {@link DocumentRefResolver}'s reference grammar. */
class DocumentRefResolverTest {

    private final DocumentRefResolver resolver = new DocumentRefResolver();

    private final DocumentRefContext skillDir =
            DocumentRefContext.of("proj", "_vance/skills/code-guard");
    private final DocumentRefContext root = DocumentRefContext.root("proj");

    @Test
    void relative_resolvesAgainstReferrerDir() {
        DocumentRef ref = resolver.resolve("guard.js", skillDir);
        assertThat(ref.projectId()).isEqualTo("proj");
        assertThat(ref.path()).isEqualTo("_vance/skills/code-guard/guard.js");
    }

    @Test
    void absolute_resolvesFromCurrentProjectRoot() {
        DocumentRef ref = resolver.resolve("/_vance/guards/x.js", skillDir);
        assertThat(ref.projectId()).isEqualTo("proj");
        assertThat(ref.path()).isEqualTo("_vance/guards/x.js");
    }

    @Test
    void authority_resolvesToOtherProject() {
        DocumentRef ref = resolver.resolve("//shared/_vance/guards/x.js", root);
        assertThat(ref.projectId()).isEqualTo("shared");
        assertThat(ref.path()).isEqualTo("_vance/guards/x.js");
    }

    @Test
    void scheme_changesNothing_whicheverFormFollowsIt() {
        // The scheme marks the reference as ours; what follows decides where
        // it lands. All three forms therefore read the same with and without.
        assertThat(resolver.resolve("vance:/_vance/guards/x.js", root))
                .isEqualTo(resolver.resolve("/_vance/guards/x.js", root));
        assertThat(resolver.resolve("vance://shared/a/b.js", root))
                .isEqualTo(resolver.resolve("//shared/a/b.js", root));
        assertThat(resolver.resolve("vance:a/b.js", skillDir))
                .isEqualTo(resolver.resolve("a/b.js", skillDir));
    }

    @Test
    void schemeWithoutSlash_isRelativeToTheReferrerDir() {
        // `vance:a/b.js` next to a skill means the file next to the skill —
        // reading it as a project-root path would silently address a
        // different document, or none.
        DocumentRef ref = resolver.resolve("vance:a/b.js", skillDir);
        assertThat(ref.projectId()).isEqualTo("proj");
        assertThat(ref.path()).isEqualTo("_vance/skills/code-guard/a/b.js");
    }

    @Test
    void query_isPreserved_fragmentDropped() {
        DocumentRef ref = resolver.resolve("//shared/doc/note.md?kind=note#top", root);
        assertThat(ref.projectId()).isEqualTo("shared");
        assertThat(ref.path()).isEqualTo("doc/note.md");
        assertThat(ref.query()).isEqualTo("kind=note");
    }

    @Test
    void dotDot_withinPath_collapses() {
        DocumentRef ref = resolver.resolve("../shared/x.js", skillDir);
        assertThat(ref.path()).isEqualTo("_vance/skills/shared/x.js");
    }

    @Test
    void dotSegments_andDoubleSlashes_collapse() {
        DocumentRef ref = resolver.resolve("/a//b/./c/", root);
        assertThat(ref.path()).isEqualTo("a/b/c");
    }

    @Test
    void dotDot_escapingRoot_throws() {
        assertThatThrownBy(() -> resolver.resolve("../../x", root))
                .isInstanceOf(DocumentRefException.class);
    }

    @Test
    void blankRef_throws() {
        assertThatThrownBy(() -> resolver.resolve("   ", root))
                .isInstanceOf(DocumentRefException.class);
    }

    @Test
    void blankAuthority_throws() {
        assertThatThrownBy(() -> resolver.resolve("///path", root))
                .isInstanceOf(DocumentRefException.class);
    }

    @Test
    void fromReferrerDocument_usesParentFolder() {
        DocumentRefContext ctx = DocumentRefContext.fromReferrerDocument(
                "proj", "_vance/skills/code-guard/skill.yaml");
        DocumentRef ref = resolver.resolve("guard.js", ctx);
        assertThat(ref.path()).isEqualTo("_vance/skills/code-guard/guard.js");
    }
}
