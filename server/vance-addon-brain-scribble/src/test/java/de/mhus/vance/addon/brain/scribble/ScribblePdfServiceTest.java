package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class ScribblePdfServiceTest {

    private static ScribbleSheet sheetWithTitle(String title) {
        ScribbleStroke stroke = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "1",
                ScribbleStroke.Width.M,
                List.of(new ScribblePoint(50, 60, 0.5), new ScribblePoint(120, 140, 0.8)));
        return new ScribbleSheet(title, ScribbleSize.a4Portrait(), List.of(stroke));
    }

    @Test
    void pdfOf_buildsOnePagePerSheet() throws IOException {
        byte[] pdf = ScribblePdfService.pdfOf(List.of(sheetWithTitle("A"), sheetWithTitle("B")));
        assertThat(pdf.length).isGreaterThan(4);
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void pdfOf_rejectsZeroSheets() {
        assertThatThrownBy(() -> ScribblePdfService.pdfOf(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathConventions() {
        assertThat(ScribblePdfService.sheetPdfPath("apps/book/notes.scribble.yaml"))
                .isEqualTo("apps/book/notes.scribble.pdf");
        assertThat(ScribblePdfService.bookPdfPath("apps/notes")).isEqualTo("apps/notes/notes.pdf");
        assertThat(ScribbleOcrService.mdPathFor("apps/book/notes.scribble.yaml"))
                .isEqualTo("apps/book/notes.scribble.md");
        // extensionless leaf keeps its name
        assertThat(ScribblePdfService.sheetPdfPath("apps/book/justname")).isEqualTo("apps/book/justname.pdf");
    }
}
