package de.mhus.vance.brain.tools.tex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TexComposeManifest} — the record that holds
 * the parsed tex-compose YAML. Tests focus on the default-resolution
 * logic ({@code effectiveEngine}, {@code effectiveOutput}) since the
 * raw field access is just record access.
 */
class TexComposeManifestTest {

    @Test
    void effectiveEngine_defaultsToPdflatex_whenNull() {
        TexComposeManifest m = new TexComposeManifest("main.tex", null, null, null, List.of("main.tex"));
        assertThat(m.effectiveEngine()).isEqualTo("pdflatex");
    }

    @Test
    void effectiveEngine_defaultsToPdflatex_whenBlank() {
        TexComposeManifest m = new TexComposeManifest("main.tex", "  ", null, null, List.of("main.tex"));
        assertThat(m.effectiveEngine()).isEqualTo("pdflatex");
    }

    @Test
    void effectiveEngine_returnsTrimmed_whenSet() {
        TexComposeManifest m = new TexComposeManifest("main.tex", " xelatex ", null, null, List.of("main.tex"));
        assertThat(m.effectiveEngine()).isEqualTo("xelatex");
    }

    @Test
    void effectiveOutput_defaultsToMainBasenamePdf_whenNull() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, null, List.of("thesis.tex"));
        assertThat(m.effectiveOutput()).isEqualTo("thesis.pdf");
    }

    @Test
    void effectiveOutput_defaultsToMainBasenamePdf_whenBlank() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, "  ", List.of("thesis.tex"));
        assertThat(m.effectiveOutput()).isEqualTo("thesis.pdf");
    }

    @Test
    void effectiveOutput_returnsTrimmed_whenSet() {
        TexComposeManifest m = new TexComposeManifest("thesis.tex", null, null, " custom.pdf ", List.of("thesis.tex"));
        assertThat(m.effectiveOutput()).isEqualTo("custom.pdf");
    }

    @Test
    void effectiveOutput_stripsTexExtension_fromMain() {
        // main without .tex extension — should still produce .pdf
        TexComposeManifest m = new TexComposeManifest("report", null, null, null, List.of("report.tex"));
        assertThat(m.effectiveOutput()).isEqualTo("report.pdf");
    }

    @Test
    void effectiveOutput_handlesNestedPathInMain() {
        TexComposeManifest m = new TexComposeManifest("chapters/intro.tex", null, null, null, List.of("chapters/intro.tex"));
        // effectiveOutput only strips .tex from the filename, doesn't touch path
        assertThat(m.effectiveOutput()).isEqualTo("chapters/intro.pdf");
    }
}
