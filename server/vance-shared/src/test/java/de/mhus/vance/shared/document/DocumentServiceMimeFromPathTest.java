package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentServiceMimeFromPathTest {

    @Test
    void markdownExtension_mapsToTextMarkdown() {
        assertThat(DocumentService.mimeFromPath("essay/outline.md"))
                .isEqualTo("text/markdown");
        assertThat(DocumentService.mimeFromPath("notes.markdown"))
                .isEqualTo("text/markdown");
    }

    @Test
    void yamlExtension_mapsToApplicationYaml() {
        assertThat(DocumentService.mimeFromPath("recipes/_user/foo.yaml"))
                .isEqualTo("application/yaml");
        assertThat(DocumentService.mimeFromPath("config.yml"))
                .isEqualTo("application/yaml");
    }

    @Test
    void jsonExtension_mapsToApplicationJson() {
        assertThat(DocumentService.mimeFromPath("audit.json"))
                .isEqualTo("application/json");
    }

    @Test
    void unknownExtension_fallsBackToTextPlain() {
        assertThat(DocumentService.mimeFromPath("foo.log"))
                .isEqualTo("text/plain");
        assertThat(DocumentService.mimeFromPath("README"))
                .isEqualTo("text/plain");
    }

    @Test
    void caseInsensitive_extensionMatch() {
        assertThat(DocumentService.mimeFromPath("File.MD"))
                .isEqualTo("text/markdown");
        assertThat(DocumentService.mimeFromPath("data.YAML"))
                .isEqualTo("application/yaml");
    }

    @Test
    void dotFileWithoutExtension_isTextPlain() {
        assertThat(DocumentService.mimeFromPath(".gitignore"))
                .isEqualTo("text/plain");
    }

    @Test
    void trailingDot_isTextPlain() {
        assertThat(DocumentService.mimeFromPath("weird."))
                .isEqualTo("text/plain");
    }
}
