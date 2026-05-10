package de.mhus.vance.brain.ai.attachment;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extract plain text from a PDF byte buffer using PDFBox 3.x.
 *
 * <p>Used as the fallback path for providers / models that don't
 * accept PDFs natively: OpenAI's langchain4j adapter (Issue #3175 —
 * {@code PdfFileContent} not implemented), Ollama (no PDF support
 * in the wire protocol), LM Studio (depends on the loaded GGUF; no
 * native PDF). The extracted text rides as a {@code TextContent}
 * block prefixed with {@code [Attachment: filename.pdf]} so the
 * model can refer back to it.
 *
 * <p>Loses the visual layout of the PDF (no images, no figures,
 * no table cell separation beyond what {@code PDFTextStripper}
 * recovers). Acceptable for text-heavy documents (specs, papers,
 * contracts); poor for layout-heavy ones (slide decks, scans).
 * For those use Anthropic / Gemini, where the PDF flows through
 * the provider's vision pipeline.
 */
@Slf4j
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    /**
     * Extract all text from {@code pdfBytes}. PDFBox is built around
     * {@code RandomAccessRead} — {@link RandomAccessReadBuffer} wraps
     * the byte array so we don't need a temp file. The wrapper is
     * close-once-via-try-with-resources for both the
     * {@link PDDocument} and its underlying buffer.
     */
    public static String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdfBytes);
             PDDocument doc = Loader.loadPDF(source)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null ? "" : text;
        } catch (IOException e) {
            throw new AttachmentException(
                    "PDF text extraction failed: " + e.getMessage(), e);
        }
    }
}
