package de.mhus.vance.brain.tools.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link DocumentTransformService}: format
 * lookup, dispatch, extension inference. Uses tiny in-test
 * transformer doubles — no Spring context.
 */
class DocumentTransformServiceTest {

    private static DocumentTransformer fakeTransformer(
            String format, String ext, java.util.function.Predicate<DocumentDocument> when) {
        return new DocumentTransformer() {
            @Override public String targetFormat()    { return format; }
            @Override public String targetMimeType()  { return "application/" + format; }
            @Override public String targetExtension() { return ext; }
            @Override public boolean canTransform(DocumentDocument source) {
                return when.test(source);
            }
            @Override public Result transform(DocumentDocument source, String title) {
                return new Result(new byte[]{1, 2, 3}, title);
            }
        };
    }

    @Test
    void supportedFormats_returnsRegisteredFormats() {
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("xlsx", "xlsx", d -> true),
                fakeTransformer("pdf", "pdf", d -> true)));
        assertThat(svc.supportedFormats()).containsExactlyInAnyOrder("xlsx", "pdf");
    }

    @Test
    void inferFormat_picksFromExtension() {
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("xlsx", "xlsx", d -> true),
                fakeTransformer("pdf", "pdf", d -> true)));
        assertThat(svc.inferFormat("reports/foo.xlsx")).isEqualTo("xlsx");
        assertThat(svc.inferFormat("foo.PDF")).isEqualTo("pdf");
    }

    @Test
    void inferFormat_returnsNullForUnknownExt() {
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("xlsx", "xlsx", d -> true)));
        assertThat(svc.inferFormat("foo.bar")).isNull();
        assertThat(svc.inferFormat("foo.docx")).isNull();
    }

    @Test
    void inferFormat_returnsNullForBlankOrMissingDot() {
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("xlsx", "xlsx", d -> true)));
        assertThat(svc.inferFormat(null)).isNull();
        assertThat(svc.inferFormat("")).isNull();
        assertThat(svc.inferFormat("noext")).isNull();
        assertThat(svc.inferFormat("foo.")).isNull();
    }

    @Test
    void dispatch_picksFirstAcceptingTransformer() {
        DocumentDocument doc = DocumentDocument.builder()
                .mimeType("text/markdown").build();
        DocumentTransformer pdfMd = fakeTransformer("pdf", "pdf",
                d -> "text/markdown".equals(d.getMimeType()));
        DocumentTransformer pdfRecords = fakeTransformer("pdf", "pdf",
                d -> "records".equals(d.getKind()));
        DocumentTransformService svc = new DocumentTransformService(List.of(
                pdfMd, pdfRecords));
        DocumentTransformer chosen = svc.dispatch(doc, "pdf");
        assertThat(chosen).isSameAs(pdfMd);
    }

    @Test
    void dispatch_throwsWhenFormatUnknown() {
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("pdf", "pdf", d -> true)));
        assertThatThrownBy(() ->
                svc.dispatch(DocumentDocument.builder().build(), "xlsx"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unsupported target format 'xlsx'");
    }

    @Test
    void dispatch_throwsWhenNoTransformerAcceptsSource() {
        DocumentDocument doc = DocumentDocument.builder()
                .path("foo.bin")
                .mimeType("application/octet-stream").build();
        DocumentTransformService svc = new DocumentTransformService(List.of(
                fakeTransformer("pdf", "pdf",
                        d -> "text/markdown".equals(d.getMimeType()))));
        assertThatThrownBy(() -> svc.dispatch(doc, "pdf"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No transformer")
                .hasMessageContaining("application/octet-stream");
    }
}
