package de.mhus.vance.brain.ai.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lifting MCP image content into documents. Without this the base64 of a
 * screenshot travels in the model's text channel — unreadable, and past
 * 32 KB replaced by a truncation stub, so not even the blob survives.
 */
class ToolImageHarvesterTest {

    private static final String PNG_BASE64 =
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    private DocumentService documentService;
    private ToolImageHarvester harvester;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        harvester = new ToolImageHarvester(
                documentService, mock(SecurityContextFactory.class), 20L * 1024 * 1024);
        DocumentDocument stored = new DocumentDocument();
        stored.setId("doc-1");
        stored.setPath("_chatbox/chrome__take_screenshot-1.png");
        when(documentService.createOrReplaceBinary(
                anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any()))
                .thenReturn(stored);
    }

    private static Map<String, Object> imageResult(String base64, String mime) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("data", base64);
        block.put("mimeType", mime);
        return Map.of("content", List.of(block));
    }

    private ToolImageHarvester.Harvest harvest(Map<String, Object> result) {
        return harvester.harvest(result, "acme", "proj", "chrome__take_screenshot", "wile.coyote");
    }

    @Test
    void imageBlock_becomesADocumentAndAnAttachmentRef() {
        ToolImageHarvester.Harvest h = harvest(imageResult(PNG_BASE64, "image/png"));

        assertThat(h.attachments()).singleElement()
                .extracting(ref -> ref.documentId()).isEqualTo("doc-1");
        verify(documentService).createOrReplaceBinary(
                anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void base64_isRemovedFromTheResult() {
        // The whole point: what the model reads must stay small.
        ToolImageHarvester.Harvest h = harvest(imageResult(PNG_BASE64, "image/png"));

        assertThat(h.result().toString()).doesNotContain(PNG_BASE64);
    }

    @Test
    void replacementBlock_saysWhereThePictureWent() {
        ToolImageHarvester.Harvest h = harvest(imageResult(PNG_BASE64, "image/png"));

        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>)
                ((List<Object>) h.result().get("content")).get(0);
        assertThat(block).containsEntry("type", "image");
        assertThat(block).containsEntry("path", "_chatbox/chrome__take_screenshot-1.png");
        assertThat(String.valueOf(block.get("note"))).contains("attached to your next turn");
    }

    @Test
    void resultWithoutImages_isReturnedUnchanged() {
        // Same instance, not a copy — this is the common path and must
        // not allocate.
        Map<String, Object> textOnly = Map.of(
                "content", List.of(Map.of("type", "text", "text", "hello")));

        ToolImageHarvester.Harvest h = harvest(textOnly);

        assertThat(h.result()).isSameAs(textOnly);
        assertThat(h.isEmpty()).isTrue();
        verify(documentService, never()).createOrReplaceBinary(
                anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void resultWithoutContentArray_isReturnedUnchanged() {
        Map<String, Object> plain = Map.of("ok", true);

        assertThat(harvest(plain).result()).isSameAs(plain);
    }

    @Test
    void textBlocksAroundTheImage_survive() {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("type", "image");
        image.put("data", PNG_BASE64);
        Map<String, Object> mixed = Map.of("content", List.of(
                Map.of("type", "text", "text", "before"),
                image,
                Map.of("type", "text", "text", "after")));

        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) harvest(mixed).result().get("content");

        assertThat(content).hasSize(3);
        assertThat(content.get(0)).isEqualTo(Map.of("type", "text", "text", "before"));
        assertThat(content.get(2)).isEqualTo(Map.of("type", "text", "text", "after"));
    }

    @Test
    void invalidBase64_leavesTheBlockAlone_insteadOfFailingTheCall() {
        ToolImageHarvester.Harvest h = harvest(imageResult("!!! not base64 !!!", "image/png"));

        assertThat(h.attachments()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>)
                ((List<Object>) h.result().get("content")).get(0);
        assertThat(block).containsEntry("data", "!!! not base64 !!!");
    }

    @Test
    void oversizeImage_isDroppedWithAReason() {
        // A model that asked for a screenshot and silently gets nothing
        // just asks again — so the block has to say why.
        ToolImageHarvester small = new ToolImageHarvester(
                documentService, mock(SecurityContextFactory.class), /*max*/ 2);

        ToolImageHarvester.Harvest h = small.harvest(
                imageResult(PNG_BASE64, "image/png"), "acme", "proj", "t", "u");

        assertThat(h.attachments()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>)
                ((List<Object>) h.result().get("content")).get(0);
        assertThat(block).containsEntry("dropped", true);
        assertThat(String.valueOf(block.get("reason"))).contains("exceeds");
    }

    @Test
    void oversizeImage_isRejectedWithoutDecoding() {
        // The gate has to run on the encoded string. Checking the decoded
        // array would bound nothing — the allocation already happened by
        // then, and the MCP stdio transport caps no response size.
        String huge = "A".repeat(4_000_000);           // ~3 MB decoded
        ToolImageHarvester small = new ToolImageHarvester(
                documentService, mock(SecurityContextFactory.class), /*max*/ 1024);

        ToolImageHarvester.Harvest h = small.harvest(
                imageResult(huge, "image/png"), "acme", "proj", "t", "u");

        assertThat(h.attachments()).isEmpty();
        verify(documentService, never()).createOrReplaceBinary(
                anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void decodedLength_matchesTheRealDecodeForPaddedAndUnpadded() {
        for (int len : new int[] {1, 2, 3, 4, 5, 30, 31, 32}) {
            byte[] raw = new byte[len];
            String encoded = Base64.getEncoder().encodeToString(raw);
            assertThat(ToolImageHarvester.decodedLength(encoded))
                    .as("length %d", len)
                    .isEqualTo(len);
        }
    }

    @Test
    void decodedLength_ignoresWrappedWhitespace() {
        // The MIME encoder wraps at 76 columns, so the payload has to be
        // past that before there is a newline to ignore at all.
        byte[] raw = new byte[300];
        String wrapped = Base64.getMimeEncoder().encodeToString(raw);
        assertThat(wrapped).contains("\n");
        assertThat(ToolImageHarvester.decodedLength(wrapped)).isEqualTo(300);
    }

    @Test
    void slug_stripsPathSyntaxFromAForeignToolName() {
        // A pack names its own tools, and the name reaches the document
        // path — "../" in it would be stored verbatim.
        assertThat(ToolImageHarvester.slug("../../escape")).isEqualTo("______escape");
        assertThat(ToolImageHarvester.slug("chrome__take_screenshot"))
                .isEqualTo("chrome__take_screenshot");
        assertThat(ToolImageHarvester.slug("")).isEqualTo("tool");
    }

    @Test
    void storageFailure_leavesTheBlockAlone() {
        when(documentService.createOrReplaceBinary(
                anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        ToolImageHarvester.Harvest h = harvest(imageResult(PNG_BASE64, "image/png"));

        assertThat(h.attachments()).isEmpty();
        assertThat(h.result()).isNotNull();
    }

    @Test
    void tooManyImages_areCappedPerResult() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("data", PNG_BASE64);
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i < ToolImageHarvester.MAX_IMAGES_PER_RESULT + 3; i++) {
            many.add(new LinkedHashMap<>(block));
        }

        ToolImageHarvester.Harvest h = harvest(Map.of("content", many));

        assertThat(h.attachments()).hasSize(ToolImageHarvester.MAX_IMAGES_PER_RESULT);
    }
}
