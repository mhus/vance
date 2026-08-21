package de.mhus.vance.shared.document.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JaglanPathsTest {

    // ─── namespace ──────────────────────────────────────────────────────

    @Test
    void isMounted_recognisesTheExtNamespaceOnly() {
        assertThat(JaglanPaths.isMounted("_ext/library/dune.pdf")).isTrue();
        assertThat(JaglanPaths.isMounted("documents/notes.md")).isFalse();
        assertThat(JaglanPaths.isMounted("_vance/recipes/default.yaml")).isFalse();
        assertThat(JaglanPaths.isMounted(null)).isFalse();
    }

    @Test
    void isMounted_rejectsTheBareRootWithoutSlash() {
        // "_extra/foo.md" must not be read as the namespace — the prefix
        // check has to include the separator.
        assertThat(JaglanPaths.isMounted("_extra/foo.md")).isFalse();
        assertThat(JaglanPaths.isMounted("_ext")).isFalse();
    }

    @Test
    void mountNameOf_takesTheFirstSegmentAfterThePrefix() {
        assertThat(JaglanPaths.mountNameOf("_ext/library/books/dune.pdf")).isEqualTo("library");
        assertThat(JaglanPaths.mountNameOf("_ext/library")).isEqualTo("library");
    }

    @Test
    void mountNameOf_rejectsAPathThatNamesNoMount() {
        assertThatThrownBy(() -> JaglanPaths.mountNameOf("_ext/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no mount");
        assertThatThrownBy(() -> JaglanPaths.mountNameOf("documents/x.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a mounted path");
    }

    @Test
    void pathInMount_isEmptyForTheMountRoot() {
        assertThat(JaglanPaths.pathInMount("_ext/library")).isEmpty();
        assertThat(JaglanPaths.pathInMount("_ext/library/")).isEmpty();
        assertThat(JaglanPaths.pathInMount("_ext/library/books/dune.pdf"))
                .isEqualTo("books/dune.pdf");
    }

    @Test
    void documentPath_roundTripsWithTheAccessors() {
        String path = JaglanPaths.documentPath("library", "books/dune.pdf");

        assertThat(path).isEqualTo("_ext/library/books/dune.pdf");
        assertThat(JaglanPaths.mountNameOf(path)).isEqualTo("library");
        assertThat(JaglanPaths.pathInMount(path)).isEqualTo("books/dune.pdf");
    }

    @Test
    void documentPath_ofTheRootIsTheMountFolder() {
        assertThat(JaglanPaths.documentPath("library", "")).isEqualTo("_ext/library");
        assertThat(JaglanPaths.documentPath("library", null)).isEqualTo("_ext/library");
    }

    // ─── mount-name grammar ─────────────────────────────────────────────

    @Test
    void mountName_acceptsLowercaseKebabAndUnderscore() {
        assertThat(JaglanPaths.isValidMountName("library")).isTrue();
        assertThat(JaglanPaths.isValidMountName("book-library")).isTrue();
        assertThat(JaglanPaths.isValidMountName("news_images")).isTrue();
        assertThat(JaglanPaths.isValidMountName("lib2")).isTrue();
        assertThat(JaglanPaths.isValidMountName("2lib")).isTrue();
    }

    @Test
    void mountName_rejectsPathSeparatorsAndTraversal() {
        // The whole reason the grammar is narrow: the name is a path segment.
        assertThat(JaglanPaths.isValidMountName("a/b")).isFalse();
        assertThat(JaglanPaths.isValidMountName("..")).isFalse();
        assertThat(JaglanPaths.isValidMountName(".")).isFalse();
        assertThat(JaglanPaths.isValidMountName("lib.v2")).isFalse();
    }

    @Test
    void mountName_rejectsLeadingUnderscoreToKeepOutOfTheSystemNamespace() {
        assertThat(JaglanPaths.isValidMountName("_vance")).isFalse();
        assertThat(JaglanPaths.isValidMountName("_library")).isFalse();
    }

    @Test
    void mountName_rejectsUppercaseWhitespaceAndEmpty() {
        assertThat(JaglanPaths.isValidMountName("Library")).isFalse();
        assertThat(JaglanPaths.isValidMountName("my library")).isFalse();
        assertThat(JaglanPaths.isValidMountName("")).isFalse();
        assertThat(JaglanPaths.isValidMountName(null)).isFalse();
    }

    @Test
    void mountName_rejectsOverLongNames() {
        assertThat(JaglanPaths.isValidMountName("a".repeat(64))).isTrue();
        assertThat(JaglanPaths.isValidMountName("a".repeat(65))).isFalse();
    }

    @Test
    void requireValidMountName_namesThePatternInTheMessage() {
        assertThatThrownBy(() -> JaglanPaths.requireValidMountName("Bad Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid mount name");
    }

    // ─── in-mount path normalisation ────────────────────────────────────

    @Test
    void normalizeInMountPath_stripsAndCollapsesSlashes() {
        assertThat(JaglanPaths.normalizeInMountPath("/books//dune.pdf/"))
                .isEqualTo("books/dune.pdf");
        assertThat(JaglanPaths.normalizeInMountPath("  books/dune.pdf  "))
                .isEqualTo("books/dune.pdf");
        assertThat(JaglanPaths.normalizeInMountPath(null)).isEmpty();
        assertThat(JaglanPaths.normalizeInMountPath("   ")).isEmpty();
    }

    @Test
    void normalizeInMountPath_refusesTraversalInsteadOfResolvingIt() {
        // Fail-closed: a resolved ".." would address a document outside the
        // mount folder and still get a valid path and a valid derived id.
        assertThatThrownBy(() -> JaglanPaths.normalizeInMountPath("books/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
        assertThatThrownBy(() -> JaglanPaths.normalizeInMountPath("./books"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    // ─── derived id ─────────────────────────────────────────────────────

    @Test
    void documentId_isStableForTheSameAddress() {
        String first = JaglanPaths.documentId("acme", "research", "library", "books/dune.pdf");
        String second = JaglanPaths.documentId("acme", "research", "library", "books/dune.pdf");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void documentId_carriesThePrefixAndIsUrlSafe() {
        String id = JaglanPaths.documentId("acme", "research", "library", "books/dune.pdf");

        assertThat(id).startsWith("ext_");
        assertThat(id).hasSize("ext_".length() + 32);
        assertThat(id).matches("ext_[0-9a-f]{32}");
    }

    @Test
    void documentId_differsPerProject() {
        // The reason tenant and project are in the digest at all: _id is
        // globally unique, so two projects mounting the same source would
        // otherwise derive one id for two documents.
        String a = JaglanPaths.documentId("acme", "research", "library", "dune.pdf");
        String b = JaglanPaths.documentId("acme", "marketing", "library", "dune.pdf");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void documentId_differsPerTenant() {
        String a = JaglanPaths.documentId("acme", "research", "library", "dune.pdf");
        String b = JaglanPaths.documentId("globex", "research", "library", "dune.pdf");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void documentId_differsPerMountAndPath() {
        String base = JaglanPaths.documentId("acme", "research", "library", "dune.pdf");

        assertThat(JaglanPaths.documentId("acme", "research", "archive", "dune.pdf"))
                .isNotEqualTo(base);
        assertThat(JaglanPaths.documentId("acme", "research", "library", "ubik.pdf"))
                .isNotEqualTo(base);
    }

    @Test
    void documentId_isNotAmbiguousAcrossFieldBoundaries() {
        // The \0 separator earns its keep here: without it these two would
        // hash identical material.
        String a = JaglanPaths.documentId("acme", "research", "lib", "rary/dune.pdf");
        String b = JaglanPaths.documentId("acme", "research", "library", "dune.pdf");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void documentId_normalisesThePathBeforeHashing() {
        String plain = JaglanPaths.documentId("acme", "research", "library", "books/dune.pdf");
        String noisy = JaglanPaths.documentId("acme", "research", "library", "/books//dune.pdf/");

        assertThat(noisy).isEqualTo(plain);
    }

    @Test
    void documentIdForPath_agreesWithTheSplitForm() {
        String viaPath = JaglanPaths.documentIdForPath(
                "acme", "research", "_ext/library/books/dune.pdf");
        String viaParts = JaglanPaths.documentId(
                "acme", "research", "library", "books/dune.pdf");

        assertThat(viaPath).isEqualTo(viaParts);
    }

    @Test
    void documentId_rejectsAnInvalidMountName() {
        assertThatThrownBy(
                () -> JaglanPaths.documentId("acme", "research", "Bad Name", "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isMountedId_distinguishesDerivedIdsFromObjectIds() {
        String derived = JaglanPaths.documentId("acme", "research", "library", "dune.pdf");

        assertThat(JaglanPaths.isMountedId(derived)).isTrue();
        // A 24-char hex ObjectId can never collide with the prefix.
        assertThat(JaglanPaths.isMountedId("507f1f77bcf86cd799439011")).isFalse();
        assertThat(JaglanPaths.isMountedId(null)).isFalse();
    }
}
