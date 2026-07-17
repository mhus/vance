package de.mhus.vance.addon.brain.tex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TexComposeManifest} — the record that holds
 * the parsed tex-compose YAML. Tests focus on the default-resolution
 * logic ({@code effectiveEngine}, {@code effectiveOutput},
 * {@code resolvePdfDocPath}) since the raw field access is just
 * record access.
 */
class TexComposeManifestTest {

    private static final List<TexComposeManifest.FileEntry> FILES =
            List.of(new TexComposeManifest.FileEntry.LocalFile("main.tex"));

    @Test
    void effectiveEngine_defaultsToPdflatex_whenNull() {
        TexComposeManifest m = new TexComposeManifest("main.tex", null, null, null, null, FILES);
        assertThat(m.effectiveEngine()).isEqualTo("pdflatex");
    }

    @Test
    void effectiveEngine_defaultsToPdflatex_whenBlank() {
        TexComposeManifest m = new TexComposeManifest("main.tex", "  ", null, null, null, FILES);
        assertThat(m.effectiveEngine()).isEqualTo("pdflatex");
    }

    @Test
    void effectiveEngine_returnsTrimmed_whenSet() {
        TexComposeManifest m = new TexComposeManifest("main.tex", " xelatex ", null, null, null, FILES);
        assertThat(m.effectiveEngine()).isEqualTo("xelatex");
    }

    @Test
    void effectiveOutput_defaultsToMainBasenamePdf_whenNull() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, null, null, FILES);
        assertThat(m.effectiveOutput()).isEqualTo("thesis.pdf");
    }

    @Test
    void effectiveOutput_defaultsToMainBasenamePdf_whenBlank() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, "  ", null, FILES);
        assertThat(m.effectiveOutput()).isEqualTo("thesis.pdf");
    }

    @Test
    void effectiveOutput_returnsTrimmed_whenSet() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, " custom.pdf ", null, FILES);
        assertThat(m.effectiveOutput()).isEqualTo("custom.pdf");
    }

    @Test
    void effectiveOutput_stripsTexExtension_fromMain() {
        // main without .tex extension — should still produce .pdf
        TexComposeManifest m = new TexComposeManifest("report", null, null, null, null, FILES);
        assertThat(m.effectiveOutput()).isEqualTo("report.pdf");
    }

    @Test
    void effectiveOutput_handlesNestedPathInMain() {
        TexComposeManifest m = new TexComposeManifest("chapters/intro.tex", null, null, null, null, FILES);
        // effectiveOutput only strips .tex from the filename, doesn't touch path
        assertThat(m.effectiveOutput()).isEqualTo("chapters/intro.pdf");
    }

    // ─── resolvePdfDocPath ───

    @Test
    void resolvePdfDocPath_defaultsToComposeDirPlusOutput() {
        TexComposeManifest m = new TexComposeManifest("hello.tex", null, null, null, null, FILES);
        assertThat(m.resolvePdfDocPath("documents/tex1")).isEqualTo("documents/tex1/hello.pdf");
    }

    @Test
    void resolvePdfDocPath_absolutePath_stripsLeadingSlash() {
        TexComposeManifest m = new TexComposeManifest("hello.tex", null, null, null, "/reports/hello.pdf", FILES);
        assertThat(m.resolvePdfDocPath("documents/tex1")).isEqualTo("reports/hello.pdf");
    }

    @Test
    void resolvePdfDocPath_relativePath_prependsComposeDir() {
        TexComposeManifest m = new TexComposeManifest("hello.tex", null, null, null, "output/hello.pdf", FILES);
        assertThat(m.resolvePdfDocPath("documents/tex1")).isEqualTo("documents/tex1/output/hello.pdf");
    }

    @Test
    void resolvePdfDocPath_emptyComposeDir_withRelativeOutputPath() {
        TexComposeManifest m = new TexComposeManifest("hello.tex", null, null, null, "hello.pdf", FILES);
        assertThat(m.resolvePdfDocPath("")).isEqualTo("hello.pdf");
    }

    @Test
    void resolvePdfDocPath_emptyComposeDir_withNoOutputPath() {
        TexComposeManifest m = new TexComposeManifest("hello.tex", null, null, null, null, FILES);
        assertThat(m.resolvePdfDocPath("")).isEqualTo("hello.pdf");
    }

    @Test
    void resolvePdfDocPath_customOutputName_usedInDefaultPath() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, "custom.pdf", null, FILES);
        assertThat(m.resolvePdfDocPath("documents/thesis")).isEqualTo("documents/thesis/custom.pdf");
    }

    @Test
    void resolvePdfDocPath_absolutePath_ignoresCustomOutputName() {
        // outputPath takes priority over output for the doc path
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, "custom.pdf", "/exports/thesis.pdf", FILES);
        assertThat(m.resolvePdfDocPath("documents/thesis")).isEqualTo("exports/thesis.pdf");
    }

    // ─── FileEntry ───

    @Test
    void localFile_targetPath_returnsPath() {
        var entry = new TexComposeManifest.FileEntry.LocalFile("thesis.tex");
        assertThat(entry.targetPath()).isEqualTo("thesis.tex");
    }

    @Test
    void crossProjectFile_targetPath_returnsTarget() {
        var entry = new TexComposeManifest.FileEntry.CrossProjectFile(
                "tud-template", "tud-report.cls", "lib/tud-report.cls");
        assertThat(entry.targetPath()).isEqualTo("lib/tud-report.cls");
    }

    @Test
    void crossProjectFile_targetPath_defaultsToPath_whenTargetEqualsPath() {
        var entry = new TexComposeManifest.FileEntry.CrossProjectFile(
                "tud-template", "images/logo.png", "images/logo.png");
        assertThat(entry.targetPath()).isEqualTo("images/logo.png");
    }
}
