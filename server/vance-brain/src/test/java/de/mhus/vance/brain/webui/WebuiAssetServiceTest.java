package de.mhus.vance.brain.webui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.BinaryLookupResult;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebuiAssetServiceTest {

    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script><path d=\"M0 0\"/></svg>";

    private DocumentService documents;
    private WebuiAssetService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        service = new WebuiAssetService(documents);
    }

    private void found(String path, byte[] bytes) {
        when(documents.lookupCascadeBytes("acme", "proj", path, WebuiAssetService.MAX_BYTES))
                .thenReturn(Optional.of(new BinaryLookupResult(path, bytes, LookupResult.Source.RESOURCE)));
    }

    @Test
    void aRasterImageIsServedAsStoredWithItsTypeAndAContentHash() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2};
        found("_vance/webui/icons/a.png", png);

        WebuiAssetService.Asset asset =
                service.find("acme", "proj", "/icons/a.png").orElseThrow();

        assertThat(asset.content()).containsExactly(png);
        assertThat(asset.contentType()).isEqualTo("image/png");
        assertThat(asset.svg()).isFalse();
        assertThat(asset.etag()).startsWith("\"").endsWith("\"").hasSize(34);
    }

    @Test
    void anSvgIsSanitizedAndTheEtagIsOfWhatIsServed() {
        found("_vance/webui/icons/s.svg", SVG.getBytes(StandardCharsets.UTF_8));

        WebuiAssetService.Asset asset =
                service.find("acme", "proj", "icons/s.svg").orElseThrow();

        String body = new String(asset.content(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("script").contains("<path");
        assertThat(asset.contentType()).isEqualTo("image/svg+xml");
        assertThat(asset.svg()).isTrue();
    }

    @Test
    void anSvgTheSanitizerRefusesIsNotServed() {
        found("_vance/webui/icons/bad.svg", "not xml".getBytes(StandardCharsets.UTF_8));

        assertThat(service.find("acme", "proj", "icons/bad.svg")).isEmpty();
    }

    @Test
    void aBadAddressNeverReachesTheDocumentLayer() {
        assertThat(service.find("acme", "proj", "../secret.png")).isEmpty();
        assertThat(service.find("acme", "proj", "icons/a.css")).isEmpty();

        verify(documents, never()).lookupCascadeBytes(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void nothingFoundIsEmpty() {
        when(documents.lookupCascadeBytes(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());

        assertThat(service.find("acme", "proj", "icons/missing.png")).isEmpty();
    }
}
