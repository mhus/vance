package de.mhus.vance.brain.tools.uitheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract of {@code ui_logo_set}: source in the current project
 * (fixed — no caller-controllable project on the read side either),
 * format gate mirroring the serving endpoint's extension priority,
 * deterministic swap (every {@code logo.*} candidate is trashed), and
 * the size cap that keeps the tenant header cheap.
 */
class UiLogoSetToolTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "work", "s", "p", "alice");

    private DocumentService documentService;
    private KindToolSupport support;
    private UiLogoSetTool tool;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        support = mock(KindToolSupport.class);
        tool = new UiLogoSetTool(documentService, support);
        // No previous logo candidates by default.
        for (String ext : TenantUiCustomization.LOGO_EXTENSION_BY_MIME.values()) {
            when(documentService.findByPath("acme", "_tenant", "_vance/config/logo." + ext))
                    .thenReturn(Optional.empty());
        }
    }

    private DocumentDocument source(String path, String mimeType, long size) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getMimeType()).thenReturn(mimeType);
        when(doc.getSize()).thenReturn(size);
        when(documentService.findByPath("acme", "work", path)).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc)).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        return doc;
    }

    /** Source plus created-result stub — the tool reads the answer fields off the created doc. */
    private DocumentDocument arrangeCreatedLogo(String sourcePath, String mimeType, String targetPath) {
        DocumentDocument sourceDoc = source(sourcePath, mimeType, 3);
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getPath()).thenReturn(targetPath);
        when(created.getMimeType()).thenReturn(mimeType);
        when(created.getSize()).thenReturn(3L);
        when(documentService.create(
                        eq("acme"), eq("_tenant"), eq(targetPath), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
        return sourceDoc;
    }

    @Test
    void copiesAPreparedPngIntoTheTenantConfig() {
        source("assets/logo.png", "image/png", 3);
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getPath()).thenReturn("_vance/config/logo.png");
        when(created.getMimeType()).thenReturn("image/png");
        when(created.getSize()).thenReturn(3L);
        when(documentService.create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/logo.png"),
                        any(),
                        any(),
                        eq("image/png"),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Map<String, Object> out = tool.invoke(Map.of("path", "assets/logo.png"), CTX);

        assertThat(out.get("path")).isEqualTo("_vance/config/logo.png");
        assertThat(out.get("mimeType")).isEqualTo("image/png");
        assertThat(out.get("replaced")).isEqualTo(java.util.List.of());
        // The extension comes from the MIME type — the write side mirrors
        // the read side's priority.
        verify(documentService)
                .create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/logo.png"),
                        any(),
                        any(),
                        eq("image/png"),
                        any(),
                        eq("alice"),
                        any());
    }

    @Test
    void enforcesCreateOnTheTenantProject() {
        arrangeCreatedLogo("assets/logo.png", "image/png", "_vance/config/logo.png");

        tool.invoke(Map.of("path", "assets/logo.png"), CTX);

        verify(support).enforceDocWrite(CTX, "_tenant", "_vance/config/logo.png", Action.CREATE);
    }

    @Test
    void rejectsUnsupportedFormats_withConversionHint() {
        source("assets/logo.gif", "image/gif", 3);

        assertThatThrownBy(() -> tool.invoke(Map.of("path", "assets/logo.gif"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("image/gif")
                .hasMessageContaining("svg, png, webp, jpg");
        verify(documentService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsOversizedSources_withShrinkHint() {
        source("assets/logo.png", "image/png", UiLogoSetTool.MAX_LOGO_BYTES + 1);

        assertThatThrownBy(() -> tool.invoke(Map.of("path", "assets/logo.png"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("image_resize");
        verify(documentService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void trashesEveryExistingLogoCandidate_soASwapCannotBeShadowed() {
        source("assets/logo.svg", "image/svg+xml", 3);
        // An old PNG logo is still around while the new one is an SVG —
        // without the cleanup the serving priority (svg beats png) would
        // be the only thing standing between the operator and their old
        // logo coming back the moment the SVG is removed.
        DocumentDocument oldPng = mock(DocumentDocument.class);
        when(oldPng.getId()).thenReturn("old-1");
        when(oldPng.getPath()).thenReturn("_vance/config/logo.png");
        when(documentService.findByPath("acme", "_tenant", "_vance/config/logo.png"))
                .thenReturn(Optional.of(oldPng));
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getPath()).thenReturn("_vance/config/logo.svg");
        when(created.getMimeType()).thenReturn("image/svg+xml");
        when(created.getSize()).thenReturn(3L);
        when(documentService.create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/logo.svg"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Map<String, Object> out = tool.invoke(Map.of("path", "assets/logo.svg"), CTX);

        verify(documentService).trash(eq("old-1"), any());
        assertThat(out.get("replaced")).isEqualTo(java.util.List.of("_vance/config/logo.png"));
    }

    @Test
    void missingSourceIsARefusal_notAnEmptyLogo() {
        when(documentService.findByPath("acme", "work", "assets/missing.png")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.invoke(Map.of("path", "assets/missing.png"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("assets/missing.png");
    }

    @Test
    void enforcesReadOnTheSource() {
        DocumentDocument doc = arrangeCreatedLogo("assets/logo.png", "image/png", "_vance/config/logo.png");

        tool.invoke(Map.of("path", "assets/logo.png"), CTX);

        verify(support).enforceDocWrite(CTX, doc, Action.READ);
    }
}
