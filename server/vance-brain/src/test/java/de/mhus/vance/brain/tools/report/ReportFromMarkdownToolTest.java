package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-helper tests for {@link ReportFromMarkdownTool}. The full
 * tool invocation needs the Spring context (DocumentService,
 * EddieContext, …) and lives in opt-in integration tests.
 */
class ReportFromMarkdownToolTest {

    @Test
    void slug_letters_digits_dashes_underscoresStayIntact() {
        assertThat(ReportFromMarkdownTool.slug("hello_world-2024"))
                .isEqualTo("hello_world-2024");
    }

    @Test
    void slug_unicodeAndPunctuationCollapseToDash() {
        assertThat(ReportFromMarkdownTool.slug("Mein Bericht: Q3-Ergebnisse!"))
                .isEqualTo("Mein-Bericht-Q3-Ergebnisse");
    }

    @Test
    void slug_collapsesAdjacentNonAlphanumericsButKeepsUnderscores() {
        // Underscores are preserved (valid in filenames); only
        // non-letter/digit/underscore/dash chars collapse to dash.
        assertThat(ReportFromMarkdownTool.slug("foo///bar"))
                .isEqualTo("foo-bar");
        assertThat(ReportFromMarkdownTool.slug("foo___bar"))
                .isEqualTo("foo___bar");
    }

    @Test
    void slug_stripsLeadingAndTrailingDashes() {
        assertThat(ReportFromMarkdownTool.slug("   --hello world--   "))
                .isEqualTo("hello-world");
    }

    @Test
    void slug_emptyOrAllPunctuationFallsBackToDefault() {
        assertThat(ReportFromMarkdownTool.slug("")).isEqualTo("report");
        assertThat(ReportFromMarkdownTool.slug("---")).isEqualTo("report");
        assertThat(ReportFromMarkdownTool.slug("###@@@")).isEqualTo("report");
    }

    @Test
    void slug_truncatesAt60Chars() {
        String long_ = "a".repeat(100);
        assertThat(ReportFromMarkdownTool.slug(long_))
                .hasSize(60);
    }

    @Test
    void defaultOutputPath_withTitleUsesSlug() {
        String path = ReportFromMarkdownTool.defaultOutputPath("My Cool Report", "pdf");
        assertThat(path)
                .startsWith("reports/My-Cool-Report-")
                .endsWith(".pdf");
    }

    @Test
    void defaultOutputPath_withoutTitleUsesGenericPrefix() {
        String path = ReportFromMarkdownTool.defaultOutputPath(null, "docx");
        assertThat(path)
                .startsWith("reports/report-")
                .endsWith(".docx");
    }
}
