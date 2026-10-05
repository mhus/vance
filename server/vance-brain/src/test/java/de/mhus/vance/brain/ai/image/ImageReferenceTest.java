package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the provider-agnostic reference-image form: the
 * base64 data-URL encoding and the blank-guard contract.
 */
class ImageReferenceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Test
    void data_url_carries_mime_and_base64() {
        ImageReference ref = new ImageReference("doc-1", "image/png", PNG);

        String url = ref.toDataUrl();

        assertThat(url).startsWith("data:image/png;base64,");
        byte[] decoded = java.util.Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
        assertThat(decoded).containsExactly(PNG);
    }

    @Test
    void constructor_rejects_blank_document_id() {
        assertThatThrownBy(() -> new ImageReference(" ", "image/png", PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejects_blank_mime() {
        assertThatThrownBy(() -> new ImageReference("doc-1", "", PNG)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejects_empty_data() {
        assertThatThrownBy(() -> new ImageReference("doc-1", "image/png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
