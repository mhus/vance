package de.mhus.vance.brain.ai.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

    @Test
    void extract_emptyByteArray_returnsEmptyString() {
        assertThat(PdfTextExtractor.extract(new byte[0])).isEmpty();
    }

    @Test
    void extract_nullBytes_returnsEmptyString() {
        assertThat(PdfTextExtractor.extract(null)).isEmpty();
    }

    @Test
    void extract_validPdf_returnsContainedText() throws IOException {
        byte[] pdf = buildPdf("Hello attachment world");

        String text = PdfTextExtractor.extract(pdf);

        assertThat(text).contains("Hello attachment world");
    }

    @Test
    void extract_garbageBytes_throwsAttachmentException() {
        byte[] garbage = "not a pdf".getBytes();

        assertThatThrownBy(() -> PdfTextExtractor.extract(garbage))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("PDF text extraction failed");
    }

    private static byte[] buildPdf(String body) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(body);
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
