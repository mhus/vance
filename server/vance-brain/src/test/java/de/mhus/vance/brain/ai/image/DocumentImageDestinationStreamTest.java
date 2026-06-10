package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentImageDestinationStreamTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";
    private static final String PATH = "images/abc-cat.png";

    @Test
    void close_commits_accumulated_bytes_with_metadata() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, "mike");

        stream.setMimeType("image/png");
        stream.setTitle("A Watercolor Cat");
        stream.setMetadata("model", "openai:gpt-image-1");
        stream.setMetadata("seed", "42");
        stream.setAltText("Cat in watercolor style");
        stream.write(new byte[] {1, 2, 3, 4, 5}, 0, 5);
        stream.close();

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCap = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCap = ArgumentCaptor.forClass(Map.class);
        verify(docs).createOrReplaceBinary(
                eq(TENANT), eq(PROJECT), eq(PATH),
                bytesCap.capture(), eq("image/png"),
                eq("A Watercolor Cat"),
                tagsCap.capture(),
                headersCap.capture(),
                eq("mike"));

        assertThat(bytesCap.getValue()).containsExactly(1, 2, 3, 4, 5);
        assertThat(tagsCap.getValue())
                .containsExactly("image", "ai-generated", "fenchurch");
        assertThat(headersCap.getValue())
                .containsEntry("model", "openai:gpt-image-1")
                .containsEntry("seed", "42")
                .containsEntry("altText", "Cat in watercolor style");
    }

    @Test
    void custom_tag_list_replaces_defaults() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null, List.of("avatar"));

        stream.setMimeType("image/png");
        stream.write(0xFF);
        stream.close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCap = ArgumentCaptor.forClass(List.class);
        verify(docs).createOrReplaceBinary(
                any(), any(), any(),
                any(byte[].class), any(),
                any(), tagsCap.capture(), any(), any());

        assertThat(tagsCap.getValue()).containsExactly("avatar");
    }

    @Test
    void close_without_mime_type_throws() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.write(1);

        assertThatThrownBy(stream::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mime type");
        verify(docs, never()).createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(), any(), any(), any(), any());
    }

    @Test
    void close_without_bytes_throws() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.setMimeType("image/png");

        assertThatThrownBy(stream::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bytes");
    }

    @Test
    void double_close_is_no_op() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.setMimeType("image/png");
        stream.write(1);
        stream.close();
        stream.close();

        verify(docs, times(1)).createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(), any(), any(), any(), any());
    }

    @Test
    void write_after_close_throws() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.setMimeType("image/png");
        stream.write(1);
        stream.close();

        assertThatThrownBy(() -> stream.write(2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stream.setMetadata("k", "v"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setMetadata_null_value_removes_key() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.setMimeType("image/png");
        stream.setMetadata("seed", "42");
        stream.setMetadata("seed", null);
        stream.write(1);
        stream.close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCap = ArgumentCaptor.forClass(Map.class);
        verify(docs).createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(),
                any(), any(), headersCap.capture(), any());

        assertThat(headersCap.getValue()).doesNotContainKey("seed");
    }

    @Test
    void setAltText_null_removes_alt_text() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);
        stream.setMimeType("image/png");
        stream.setAltText("a cat");
        stream.setAltText(null);
        stream.write(1);
        stream.close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCap = ArgumentCaptor.forClass(Map.class);
        verify(docs).createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(),
                any(), any(), headersCap.capture(), any());

        assertThat(headersCap.getValue()).doesNotContainKey("altText");
    }

    @Test
    void setMimeType_rejects_blank() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);

        assertThatThrownBy(() -> stream.setMimeType(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setMetadata_rejects_blank_key() {
        DocumentService docs = mock(DocumentService.class);
        DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                docs, TENANT, PROJECT, PATH, null);

        assertThatThrownBy(() -> stream.setMetadata(" ", "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
