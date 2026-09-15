package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DesignerPathsTest {

    // ── normaliseFolder ────────────────────────────────────────────

    @Test
    void normaliseFolder_stripsSlashes() {
        assertThat(DesignerPaths.normaliseFolder("/designs/")).isEqualTo("designs");
        assertThat(DesignerPaths.normaliseFolder(" a/b/c ")).isEqualTo("a/b/c");
    }

    @Test
    void normaliseFolder_rejectsBlank() {
        assertThatThrownBy(() -> DesignerPaths.normaliseFolder("///")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DesignerPaths.normaliseFolder(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── designNameOf ───────────────────────────────────────────────

    @Test
    void designNameOf_extractsDesignSegment() {
        assertThat(DesignerPaths.designNameOf("designs", "designs/landing/index.html"))
                .contains("landing");
        assertThat(DesignerPaths.designNameOf("designs", "designs/landing/assets/logo.png"))
                .contains("landing");
    }

    @Test
    void designNameOf_ignoresLooseFilesManifestsAndForeignPaths() {
        assertThat(DesignerPaths.designNameOf("designs", "designs/_app.yaml")).isEmpty();
        assertThat(DesignerPaths.designNameOf("designs", "designs/_index.md")).isEmpty();
        assertThat(DesignerPaths.designNameOf("designs", "designs/readme.txt")).isEmpty();
        assertThat(DesignerPaths.designNameOf("designs", "other/landing/index.html"))
                .isEmpty();
        assertThat(DesignerPaths.designNameOf("a/b", "a/landing/index.html")).isEmpty();
    }

    @Test
    void designNameOf_reservesUnderscoreNames() {
        // A folder starting with "_" is generated/reserved space, never a design.
        assertThat(DesignerPaths.designNameOf("designs", "designs/_drafts/index.html"))
                .isEmpty();
    }

    // ── resolveFile ────────────────────────────────────────────────

    @Test
    void resolveFile_emptyInnerPathMapsToEntryFile() {
        assertThat(DesignerPaths.resolveFile("designs", "landing", "")).contains("designs/landing/index.html");
        assertThat(DesignerPaths.resolveFile("designs", "landing", "/")).contains("designs/landing/index.html");
        assertThat(DesignerPaths.resolveFile("designs", "landing", null)).contains("designs/landing/index.html");
    }

    @Test
    void resolveFile_joinsNestedInnerPaths() {
        assertThat(DesignerPaths.resolveFile("designs", "landing", "assets/css/main.css"))
                .contains("designs/landing/assets/css/main.css");
    }

    @Test
    void resolveFile_rejectsTraversal() {
        assertThat(DesignerPaths.resolveFile("designs", "landing", "../_app.yaml"))
                .isEmpty();
        assertThat(DesignerPaths.resolveFile("designs", "landing", "a/../../other/x.css"))
                .isEmpty();
        assertThat(DesignerPaths.resolveFile("designs", "landing", "..")).isEmpty();
        assertThat(DesignerPaths.resolveFile("designs", "landing", "assets/./x.css"))
                .isEmpty();
    }

    @Test
    void resolveFile_rejectsSegmentSeparatorSmuggling() {
        // An encoded slash decoded by the controller must not turn one
        // design into another: the design name is a single segment.
        assertThat(DesignerPaths.resolveFile("designs", "landing/x", "index.html"))
                .isEmpty();
        assertThat(DesignerPaths.resolveFile("designs", "a\\b", "index.html")).isEmpty();
        // Absolute inner paths are stripped to relative — but a smuggled
        // ".." still must not survive.
        assertThat(DesignerPaths.resolveFile("designs", "landing", "/../secret.txt"))
                .isEmpty();
    }

    @Test
    void resolveFile_rejectsReservedAndEmptyDesignNames() {
        assertThat(DesignerPaths.resolveFile("designs", "_app.yaml", "")).isEmpty();
        assertThat(DesignerPaths.resolveFile("designs", "", "")).isEmpty();
    }

    @Test
    void resolveFile_staysInsideTheDesignFolder() {
        // The composition guarantee: every accepted result is a prefixed
        // path under the design folder — verified, not assumed.
        Optional<String> resolved = DesignerPaths.resolveFile("designs", "landing", "a/b/c.css");
        assertThat(resolved).contains("designs/landing/a/b/c.css");
        assertThat(resolved.get()).startsWith("designs/landing/");
    }
}
