package de.mhus.vance.liver.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PathMapper} — the deterministic mapping from a
 * Vance document path to a safe file path
 * ({@code planning/user-backup.md} §4.5).
 *
 * <p>The mapping rules are exhaustive but small. Each test pins one
 * rule so a refactor that quietly changes the contract (for example,
 * a different collision suffix, or a different choice of escape
 * character) cannot land without breaking tests.
 */
class PathMapperTest {

    @Test
    void emptyPath_becomesUnderscore() {
        assertThat(PathMapper.map("")).isEqualTo("_");
    }

    @Test
    void plainPath_passesThrough() {
        assertThat(PathMapper.map("notes/idee.md")).isEqualTo("notes/idee.md");
    }

    @Test
    void trailingSlash_collapsesToUnderscore() {
        // A trailing slash on a Vance document path means
        // "directory-shaped" — at the FS level this just means
        // "another segment that happens to be empty", which our
        // mapper turns into "_". That round-trip is what makes
        // find-by-path in the manifest stable.
        assertThat(PathMapper.map("foo/")).isEqualTo("foo/_");
    }

    @Test
    void illegalCharacters_areEscaped() {
        // Plan §4.5: Windows-illegal chars become %XX (UTF-8 hex).
        // ? is 0x3F in ASCII — one byte.
        assertThat(PathMapper.map("notes/what?.md")).isEqualTo("notes/what%3F.md");
        // * is 0x2A.
        assertThat(PathMapper.map("a*b.md")).isEqualTo("a%2Ab.md");
        // : is 0x3A.
        assertThat(PathMapper.map("a:b")).isEqualTo("a%3Ab");
    }

    @Test
    void controlCharacters_areEscaped() {
        // BEL (0x07) and NUL (0x00) round-trip via %07 and %00.
        assertThat(PathMapper.map("a\u0007b")).isEqualTo("a%07b");
        assertThat(PathMapper.map("a\u0000b")).isEqualTo("a%00b");
    }

    @Test
    void multibyteCharacters_areUtf8Escaped() {
        // ü is U+00FC, UTF-8 0xC3 0xBC.
        assertThat(PathMapper.map("hütte.md")).isEqualTo("h%C3%BCtte.md");
    }

    @Test
    void trailingDotsAreStripped() {
        // Windows: "foo." == "foo" — strip so the path round-trips.
        assertThat(PathMapper.map("foo.")).isEqualTo("foo");
        assertThat(PathMapper.map("foo...")).isEqualTo("foo");
    }

    @Test
    void trailingSpacesAreStripped() {
        assertThat(PathMapper.map("foo ")).isEqualTo("foo");
    }

    @Test
    void reservedDeviceName_isPrefixed() {
        assertThat(PathMapper.map("CON")).isEqualTo("_CON");
        assertThat(PathMapper.map("nul")).isEqualTo("_nul"); // case-insensitive
        assertThat(PathMapper.map("COM1")).isEqualTo("_COM1");
        assertThat(PathMapper.map("LPT9")).isEqualTo("_LPT9");
    }

    @Test
    void reservedDeviceName_withExtension_passesThrough() {
        // Windows allows CON.md, COM1.txt etc. — the reserved name
        // only matters when the segment is exactly the reserved word.
        assertThat(PathMapper.map("CON.md")).isEqualTo("CON.md");
        assertThat(PathMapper.map("COM1.log")).isEqualTo("COM1.log");
    }

    @Test
    void slashInsideSegment_isEscaped() {
        // map() splits at /, so we test mapSegment directly. Slashes
        // inside a single segment get percent-encoded — useful when
        // the server hands us a single path component that contains
        // a slash (rare but possible via kits or mounting).
        assertThat(PathMapper.mapSegment("foo/bar")).isEqualTo("foo%2Fbar");
    }

    @Test
    void collisionVariant_firstAttempt_returnsOriginal() {
        assertThat(PathMapper.collisionVariant("notes/foo.md", 1)).isEqualTo("notes/foo.md");
    }

    @Test
    void collisionVariant_insertsBeforeExtension() {
        assertThat(PathMapper.collisionVariant("notes/foo.md", 2)).isEqualTo("notes/foo~2.md");
        assertThat(PathMapper.collisionVariant("notes/foo.md", 5)).isEqualTo("notes/foo~5.md");
    }

    @Test
    void collisionVariant_noExtension_appendsAtEnd() {
        assertThat(PathMapper.collisionVariant("notes/foo", 2)).isEqualTo("notes/foo~2");
    }

    @Test
    void collisionVariant_dotInFolderName_isNotAnExtension() {
        // "foo.bar/baz" — the dot is in the folder name, not the
        // file extension. The suffix lands on the end.
        assertThat(PathMapper.collisionVariant("foo.bar/baz", 2)).isEqualTo("foo.bar/baz~2");
    }

    @Test
    void collisionVariant_zero_throws() {
        assertThatThrownBy(() -> PathMapper.collisionVariant("foo.md", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isDirectoryPath_detectsTrailingSlash() {
        assertThat(PathMapper.isDirectoryPath("foo/")).isTrue();
        assertThat(PathMapper.isDirectoryPath("foo")).isFalse();
        assertThat(PathMapper.isDirectoryPath("")).isFalse();
    }

    @Test
    void folderConflictSuffix_exposed() {
        assertThat(PathMapper.folderConflictSuffix()).isEqualTo("~dir");
    }

    @Test
    void mappingIsDeterministic() {
        // Two runs of the same input must produce the same output —
        // that's what makes manifest verification possible.
        String once = PathMapper.map("wiki/drafts/heizung?.md");
        String twice = PathMapper.map("wiki/drafts/heizung?.md");
        assertThat(once).isEqualTo(twice);
    }
}
