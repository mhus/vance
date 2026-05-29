package de.mhus.vance.brain.tools.transform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransformDocumentToolTest {

    @Test
    void slug_keepsLettersDigitsUnderscoresAndDashes() {
        assertThat(TransformDocumentTool.slug("hello_world-2026"))
                .isEqualTo("hello_world-2026");
    }

    @Test
    void slug_collapsesPunctuationToDash() {
        assertThat(TransformDocumentTool.slug("Q3 Sales: 2026!"))
                .isEqualTo("Q3-Sales-2026");
    }

    @Test
    void slug_fallsBackToDocumentForEmpty() {
        assertThat(TransformDocumentTool.slug("###"))
                .isEqualTo("document");
        assertThat(TransformDocumentTool.slug(""))
                .isEqualTo("document");
    }

    @Test
    void slug_truncatesAt60() {
        assertThat(TransformDocumentTool.slug("a".repeat(120)))
                .hasSize(60);
    }

    @Test
    void defaultOutputPath_buildsExpectedShape() {
        String p = TransformDocumentTool.defaultOutputPath("My Report", "xlsx");
        assertThat(p).startsWith("reports/My-Report-").endsWith(".xlsx");
    }
}
