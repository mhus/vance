package de.mhus.vance.brain.tools.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link PdfReadTool}: page-range parsing
 * against a known page count. The PDFBox roundtrip itself is opt-
 * in territory (needs a real PDF) and lives in integration tests.
 */
class PdfReadToolTest {

    // ─── parsePages: defaults + clamps ──────────────────────────────

    @Test
    void parsePages_blankUsesWholeDocument() {
        PdfReadTool.PageRange r = PdfReadTool.parsePages(null, 10);
        assertThat(r.start()).isEqualTo(1);
        assertThat(r.end()).isEqualTo(10);
    }

    @Test
    void parsePages_emptyStringUsesWholeDocument() {
        assertThat(PdfReadTool.parsePages("  ", 5))
                .isEqualTo(new PdfReadTool.PageRange(1, 5));
    }

    @Test
    void parsePages_singlePage() {
        assertThat(PdfReadTool.parsePages("3", 10))
                .isEqualTo(new PdfReadTool.PageRange(3, 3));
    }

    @Test
    void parsePages_inclusiveRange() {
        assertThat(PdfReadTool.parsePages("2-5", 10))
                .isEqualTo(new PdfReadTool.PageRange(2, 5));
    }

    @Test
    void parsePages_openEndedRightToEnd() {
        assertThat(PdfReadTool.parsePages("7-", 10))
                .isEqualTo(new PdfReadTool.PageRange(7, 10));
    }

    @Test
    void parsePages_openEndedLeftFromStart() {
        assertThat(PdfReadTool.parsePages("-4", 10))
                .isEqualTo(new PdfReadTool.PageRange(1, 4));
    }

    @Test
    void parsePages_endBeyondCountIsSilentlyClamped() {
        assertThat(PdfReadTool.parsePages("1-100", 10))
                .isEqualTo(new PdfReadTool.PageRange(1, 10));
    }

    @Test
    void parsePages_zeroPageDocReturnsEmptyRange() {
        PdfReadTool.PageRange r = PdfReadTool.parsePages(null, 0);
        assertThat(r.start()).isEqualTo(1);
        assertThat(r.end()).isEqualTo(0);
    }

    // ─── parsePages: error paths ────────────────────────────────────

    @Test
    void parsePages_zeroStartIsRejected() {
        assertThatThrownBy(() -> PdfReadTool.parsePages("0-3", 10))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("start must be >= 1");
    }

    @Test
    void parsePages_endBeforeStartIsRejected() {
        assertThatThrownBy(() -> PdfReadTool.parsePages("5-2", 10))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("end (2) < start (5)");
    }

    @Test
    void parsePages_startBeyondPdfIsRejected() {
        assertThatThrownBy(() -> PdfReadTool.parsePages("20", 10))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("beyond the PDF");
    }

    @Test
    void parsePages_nonNumericIsRejected() {
        assertThatThrownBy(() -> PdfReadTool.parsePages("abc", 10))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not an integer");
    }

    @Test
    void parsePages_nonNumericRangePartIsRejected() {
        assertThatThrownBy(() -> PdfReadTool.parsePages("1-xyz", 10))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not an integer");
    }

    // ─── formatLabel ────────────────────────────────────────────────

    @Test
    void formatLabel_allRange() {
        PdfReadTool.PageRange r = new PdfReadTool.PageRange(1, 10);
        assertThat(r.formatLabel(10)).isEqualTo("all");
    }

    @Test
    void formatLabel_singlePage() {
        PdfReadTool.PageRange r = new PdfReadTool.PageRange(3, 3);
        assertThat(r.formatLabel(10)).isEqualTo("3");
    }

    @Test
    void formatLabel_explicitRange() {
        PdfReadTool.PageRange r = new PdfReadTool.PageRange(2, 5);
        assertThat(r.formatLabel(10)).isEqualTo("2-5");
    }
}
