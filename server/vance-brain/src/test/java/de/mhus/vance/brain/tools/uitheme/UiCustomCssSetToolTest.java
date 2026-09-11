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
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract of the CSS setter pair: fixed {@code _tenant} target (no
 * caller-controllable project at all), sanitizer runs <b>at write
 * time</b> with the result reported, upsert semantics of
 * {@code doc_write} (create vs overwrite), and the refusal path that
 * hands a non-admin caller back to the operator.
 */
class UiCustomCssSetToolTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "work", "s", "p", "alice");

    private DocumentService documentService;
    private KindToolSupport support;
    private UiCustomCssSetTool setTool;
    private UiCustomCssGetTool getTool;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        support = mock(KindToolSupport.class);
        setTool = new UiCustomCssSetTool(documentService, support);
        getTool = new UiCustomCssGetTool(documentService, support);
    }

    @Test
    void set_sanitizesBeforeStoring_andReportsIt() {
        when(documentService.findByPath("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.empty());
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getId()).thenReturn("d1");
        when(documentService.create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/custom.css"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Map<String, Object> out =
                setTool.invoke(Map.of("content", "body { color: red; } @import 'https://evil/x.css';"), CTX);

        assertThat(out.get("sanitized")).isEqualTo(true);

        // The stored body is the sanitized one — the @import never lands.
        org.mockito.ArgumentCaptor<java.io.InputStream> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(documentService)
                .create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/custom.css"),
                        any(),
                        any(),
                        eq("text/css"),
                        contentCaptor.capture(),
                        any(),
                        any());
        String stored;
        try (java.io.InputStream in = contentCaptor.getValue()) {
            stored = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(stored).isEqualTo("body { color: red; } ").doesNotContain("evil");
    }

    @Test
    void set_cleanCssPassesUntouched() {
        when(documentService.findByPath("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.empty());
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getId()).thenReturn("d1");
        when(documentService.create(
                        eq("acme"),
                        eq("_tenant"),
                        eq("_vance/config/custom.css"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Map<String, Object> out = setTool.invoke(Map.of("content", ".btn-primary { background: red; }"), CTX);

        assertThat(out.get("sanitized")).isEqualTo(false);
        assertThat(out.get("overwritten")).isEqualTo(false);
        assertThat(out.get("path")).isEqualTo("_vance/config/custom.css");
    }

    @Test
    void set_existingDocumentIsOverwritten_notDuplicated() {
        DocumentDocument existing = mock(DocumentDocument.class);
        when(existing.getId()).thenReturn("d0");
        when(documentService.findByPath("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.of(existing));
        DocumentDocument updated = mock(DocumentDocument.class);
        when(updated.getId()).thenReturn("d0");
        when(documentService.update(eq("d0"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);

        Map<String, Object> out = setTool.invoke(Map.of("content", "a { color: red }"), CTX);

        assertThat(out.get("overwritten")).isEqualTo(true);
        verify(documentService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void set_enforcesCreateOnTheTenantProject_beforeAnythingIsWritten() {
        when(documentService.findByPath("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new de.mhus.vance.shared.permission.PermissionDeniedException(
                        de.mhus.vance.shared.permission.SecurityContext.user("alice", "acme", java.util.List.of()),
                        new de.mhus.vance.shared.permission.Resource.Document(
                                "acme", "_tenant", "_vance/config/custom.css"),
                        Action.CREATE))
                .when(support)
                .enforceDocWrite(CTX, "_tenant", "_vance/config/custom.css", Action.CREATE);

        assertThatThrownBy(() -> setTool.invoke(Map.of("content", "a{}"), CTX))
                .isInstanceOf(de.mhus.vance.shared.permission.PermissionDeniedException.class);
        verify(documentService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void get_returnsTheCurrentBody_emptyWhenUnset() {
        when(documentService.lookupCascade("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.empty());
        assertThat(getTool.invoke(Map.of(), CTX).get("content")).isEqualTo("");

        when(documentService.lookupCascade("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.of(
                        new LookupResult("_vance/config/custom.css", "body{}", LookupResult.Source.VANCE, null)));
        assertThat(getTool.invoke(Map.of(), CTX).get("content")).isEqualTo("body{}");
    }

    @Test
    void get_enforcesRead_memberOrNotIsTheResolversDecision() {
        when(documentService.lookupCascade("acme", "_tenant", "_vance/config/custom.css"))
                .thenReturn(Optional.empty());

        getTool.invoke(Map.of(), CTX);

        verify(support).enforceDocWrite(CTX, "_tenant", "_vance/config/custom.css", Action.READ);
    }

    @Test
    void set_missingContentIsARefusalNotAnEmptyStylesheet() {
        assertThatThrownBy(() -> setTool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("content");
    }
}
