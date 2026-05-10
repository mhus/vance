package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.attachment.AttachmentException;
import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandardAiChatContentBlockTest {

    private static final String CHAT_NAME = "anthropic:claude-sonnet-4-5";

    @Test
    void image_withVisionCapability_emitsImageContent() {
        ResolvedAttachment att = imageAttachment();
        Content block = StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.ANTHROPIC, Set.of(ModelCapability.VISION));

        assertThat(block).isInstanceOf(ImageContent.class);
    }

    @Test
    void image_withoutVisionCapability_fails() {
        ResolvedAttachment att = imageAttachment();

        assertThatThrownBy(() -> StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.ANTHROPIC, Set.of()))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("VISION capability");
    }

    @Test
    void pdf_anthropicWithPdfCapability_emitsPdfFileContent() {
        ResolvedAttachment att = pdfAttachment();
        Content block = StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.ANTHROPIC, Set.of(ModelCapability.PDF));

        assertThat(block).isInstanceOf(PdfFileContent.class);
    }

    @Test
    void pdf_geminiWithPdfCapability_emitsPdfFileContent() {
        ResolvedAttachment att = pdfAttachment();
        Content block = StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.GEMINI, Set.of(ModelCapability.PDF));

        assertThat(block).isInstanceOf(PdfFileContent.class);
    }

    @Test
    void pdf_openaiWithoutNativePdfPath_fallsBackToText() {
        // OpenAI is intentionally NOT in StandardAiChat's NATIVE_PDF_PROVIDERS
        // because the langchain4j-open-ai 1.14 adapter has no
        // PdfFileContent support. Even with PDF capability declared,
        // we extract text via PDFBox.
        byte[] tinyPdf = makeMinimalPdf();
        ResolvedAttachment att = new ResolvedAttachment(
                "doc-1", "application/pdf", tinyPdf, "spec.pdf");

        Content block = StandardAiChat.toContentBlock(
                att, "openai:gpt-4o", ProviderType.OPENAI,
                Set.of(ModelCapability.PDF, ModelCapability.VISION));

        assertThat(block).isInstanceOf(TextContent.class);
        TextContent text = (TextContent) block;
        assertThat(text.text()).startsWith("[Attachment: spec.pdf — PDF text extract]");
    }

    @Test
    void pdf_anthropicWithoutPdfCapability_fallsBackToText() {
        byte[] tinyPdf = makeMinimalPdf();
        ResolvedAttachment att = new ResolvedAttachment(
                "doc-1", "application/pdf", tinyPdf, "spec.pdf");

        Content block = StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.ANTHROPIC,
                Set.of(ModelCapability.VISION));

        assertThat(block).isInstanceOf(TextContent.class);
    }

    @Test
    void textAttachment_emitsTextContentWithFilenamePrefix() {
        byte[] markdown = "# Heading\n\nbody".getBytes();
        ResolvedAttachment att = new ResolvedAttachment(
                "doc-md", "text/markdown", markdown, "notes.md");

        Content block = StandardAiChat.toContentBlock(
                att, CHAT_NAME, ProviderType.ANTHROPIC, Set.of());

        assertThat(block).isInstanceOf(TextContent.class);
        TextContent text = (TextContent) block;
        assertThat(text.text()).startsWith("[Attachment: notes.md]");
        assertThat(text.text()).contains("# Heading");
    }

    private static ResolvedAttachment imageAttachment() {
        return new ResolvedAttachment(
                "doc-img", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, "pic.png");
    }

    private static ResolvedAttachment pdfAttachment() {
        return new ResolvedAttachment(
                "doc-pdf", "application/pdf", makeMinimalPdf(), "doc.pdf");
    }

    /**
     * Build a tiny but parseable PDF — PDFBox is strict, so we hand it
     * a PDF actually produced by PDFBox via the same builder path.
     */
    private static byte[] makeMinimalPdf() {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
