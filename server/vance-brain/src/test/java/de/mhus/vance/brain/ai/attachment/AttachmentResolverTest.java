package de.mhus.vance.brain.ai.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachmentResolverTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";

    private DocumentService documentService;
    private AttachmentResolver resolver;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        resolver = new AttachmentResolver(documentService, 1024, 4096);
    }

    @Test
    void emptyOrNullInput_returnsEmptyList() {
        assertThat(resolver.resolveAll(null, TENANT, PROJECT)).isEmpty();
        assertThat(resolver.resolveAll(List.of(), TENANT, PROJECT)).isEmpty();
    }

    @Test
    void unknownDocumentId_throwsAttachmentException() {
        when(documentService.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                resolver.resolveAll(List.of(new AttachmentRef("missing")), TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void crossProjectDocument_throwsAttachmentException() {
        DocumentDocument doc = sampleDocument("doc-1", TENANT, "OTHER_PROJECT",
                "image/png", new byte[]{1, 2, 3});
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(any())).thenReturn(new ByteArrayInputStream(doc.getInlineText().getBytes()));

        assertThatThrownBy(() ->
                resolver.resolveAll(List.of(new AttachmentRef("doc-1")), TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("not accessible in this scope");
    }

    @Test
    void crossTenantDocument_throwsAttachmentException() {
        DocumentDocument doc = sampleDocument("doc-1", "OTHER_TENANT", PROJECT,
                "image/png", new byte[]{1, 2, 3});
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() ->
                resolver.resolveAll(List.of(new AttachmentRef("doc-1")), TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("not accessible");
    }

    @Test
    void disallowedMime_throwsAttachmentException() {
        DocumentDocument doc = sampleDocument("doc-1", TENANT, PROJECT,
                "application/x-binary", new byte[]{1, 2, 3});
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() ->
                resolver.resolveAll(List.of(new AttachmentRef("doc-1")), TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("MIME type not allowed");
    }

    @Test
    void perFileLimit_throwsWhenExceeded() {
        byte[] big = new byte[2048]; // > 1024 limit
        DocumentDocument doc = sampleDocument("doc-1", TENANT, PROJECT,
                "application/pdf", big);
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() ->
                resolver.resolveAll(List.of(new AttachmentRef("doc-1")), TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("per-file limit");
    }

    @Test
    void perRequestLimit_throwsWhenAggregateExceeded() {
        // 4 docs of 1024 bytes each, request limit is 4096 — must trip on 5th attempt
        byte[] data = new byte[1024];
        for (int i = 1; i <= 5; i++) {
            DocumentDocument d = sampleDocument("doc-" + i, TENANT, PROJECT,
                    "image/png", data);
            when(documentService.findById("doc-" + i)).thenReturn(Optional.of(d));
            when(documentService.loadContent(d)).thenReturn(new ByteArrayInputStream(data));
        }
        List<AttachmentRef> refs = List.of(
                new AttachmentRef("doc-1"),
                new AttachmentRef("doc-2"),
                new AttachmentRef("doc-3"),
                new AttachmentRef("doc-4"),
                new AttachmentRef("doc-5"));

        assertThatThrownBy(() -> resolver.resolveAll(refs, TENANT, PROJECT))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining("per-request limit");
    }

    @Test
    void validImage_resolvesToImageAttachment() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        DocumentDocument doc = sampleDocument("doc-img", TENANT, PROJECT,
                "image/png", png);
        when(documentService.findById("doc-img")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(png));

        List<ResolvedAttachment> resolved = resolver.resolveAll(
                List.of(new AttachmentRef("doc-img")), TENANT, PROJECT);

        assertThat(resolved).hasSize(1);
        ResolvedAttachment a = resolved.get(0);
        assertThat(a.documentId()).isEqualTo("doc-img");
        assertThat(a.mimeType()).isEqualTo("image/png");
        assertThat(a.isImage()).isTrue();
        assertThat(a.isPdf()).isFalse();
        assertThat(a.data()).containsExactly(png);
    }

    @Test
    void mimeTypeIsNormalisedToLowercase() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        DocumentDocument doc = sampleDocument("doc-img", TENANT, PROJECT,
                "Image/PNG", png);
        when(documentService.findById("doc-img")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(png));

        List<ResolvedAttachment> resolved = resolver.resolveAll(
                List.of(new AttachmentRef("doc-img")), TENANT, PROJECT);

        assertThat(resolved.get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    void filenameDerivedFromPathWhenNameMissing() {
        byte[] data = "hello".getBytes();
        DocumentDocument doc = sampleDocument("doc", TENANT, PROJECT,
                "text/markdown", data);
        doc.setName(""); // simulate bare doc
        doc.setPath("notes/sub/thesis-ch1.md");
        when(documentService.findById("doc")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(data));

        List<ResolvedAttachment> resolved = resolver.resolveAll(
                List.of(new AttachmentRef("doc")), TENANT, PROJECT);

        assertThat(resolved.get(0).originalFilename()).isEqualTo("thesis-ch1.md");
    }

    private static DocumentDocument sampleDocument(
            String id, String tenant, String project, String mimeType, byte[] data) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId(id);
        doc.setTenantId(tenant);
        doc.setProjectId(project);
        doc.setName("file.bin");
        doc.setPath("file.bin");
        doc.setMimeType(mimeType);
        doc.setSize(data.length);
        // Use inlineText so loadContent returns something deterministic
        // when the test stub doesn't override it.
        doc.setInlineText(new String(data));
        return doc;
    }
}
