package de.mhus.vance.brain.documents.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentSummaryDriverTest {

    private LightLlmService lightLlm;
    private DocumentService documentService;
    private DocumentSummaryDriver driver;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        documentService = mock(DocumentService.class);
        driver = new DocumentSummaryDriver(lightLlm, documentService);
        ReflectionTestUtils.setField(driver, "maxContentBytes", 200_000);
    }

    @Test
    void run_persistsSummaryAndTags_andRemarksRagDirty() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-1", "notes/auth.md", "Auth notes", "JWT rotation plan.");
        when(lightLlm.callForJson(any())).thenReturn(reply(
                "JWT rotation policy across services.",
                List.of("auth", "jwt", "rotation")));

        driver.run(project, doc);

        verify(documentService).writeSummary("doc-1",
                "JWT rotation policy across services.",
                List.of("auth", "jwt", "rotation"));
        verify(documentService).markRagDirty("doc-1");
    }

    @Test
    void run_passesPathTitleContentAsPebbleVars() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-1", "notes/auth.md", "Auth notes", "Body.");
        when(lightLlm.callForJson(any())).thenReturn(reply("s", List.of("t")));

        driver.run(project, doc);

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo(DocumentSummaryDriver.RECIPE_NAME);
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("lit-review");
        assertThat(req.getUserPrompt()).isEqualTo("notes/auth.md");
        assertThat(req.getPebbleVars())
                .containsEntry("path", "notes/auth.md")
                .containsEntry("title", "Auth notes")
                .containsEntry("content", "Body.");
    }

    @Test
    void run_omitsTitleWhenBlank() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-2", "notes/x.md", null, "Body.");
        when(lightLlm.callForJson(any())).thenReturn(reply("s", List.of("t")));

        driver.run(project, doc);

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        assertThat(cap.getValue().getPebbleVars()).doesNotContainKey("title");
    }

    @Test
    void run_dropsBlankTagEntries() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-3", "x.md", null, "y");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(DocumentSummaryDriver.FIELD_SUMMARY, "ok.");
        // Mix valid + blank + non-string entries — only valid ones survive.
        raw.put(DocumentSummaryDriver.FIELD_TAGS,
                java.util.Arrays.asList("auth", " ", "", "rotation", null, 42));
        when(lightLlm.callForJson(any())).thenReturn(raw);

        driver.run(project, doc);

        verify(documentService).writeSummary(eq("doc-3"), eq("ok."),
                eq(List.of("auth", "rotation")));
    }

    @Test
    void run_schemaBudgetExhausted_throws_withDocContext() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-4", "x.md", null, "y");
        when(lightLlm.callForJson(any()))
                .thenThrow(new SchemaValidationException(2, Map.of(), "missing 'summary'"));

        assertThatThrownBy(() -> driver.run(project, doc))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("doc-4")
                .hasMessageContaining("attempts=2");
        verify(documentService, never()).writeSummary(any(), any(), any());
        verify(documentService, never()).markRagDirty(any());
    }

    @Test
    void run_lightLlmFailure_throws_withDocContext() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-5", "x.md", null, "y");
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("provider 503"));

        assertThatThrownBy(() -> driver.run(project, doc))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("doc-5")
                .hasMessageContaining("provider 503");
        verify(documentService, never()).writeSummary(any(), any(), any());
    }

    @Test
    void run_blankSummary_throws_andSkipsPersist() {
        ProjectDocument project = project("acme", "lit-review");
        DocumentDocument doc = inlineDoc("doc-6", "x.md", null, "y");
        when(lightLlm.callForJson(any())).thenReturn(reply("   ", List.of("t")));

        assertThatThrownBy(() -> driver.run(project, doc))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("doc-6")
                .hasMessageContaining("'summary'");
        verify(documentService, never()).writeSummary(any(), any(), any());
    }

    @Test
    void truncate_largeContent_keepsHeadAndTailWithMarker() {
        String content = "a".repeat(500_000);
        String truncated = DocumentSummaryDriver.truncate(content, 1_000);
        assertThat(truncated)
                .contains("[... truncated ...]")
                .hasSizeLessThan(content.length());
        // Roughly head + tail + marker — never balloons past the cap by much.
        assertThat(truncated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(1_200);
    }

    @Test
    void truncate_smallContent_isUntouched() {
        String content = "short body";
        assertThat(DocumentSummaryDriver.truncate(content, 1_000)).isEqualTo(content);
    }

    // ──────────────────── helpers ────────────────────

    private static ProjectDocument project(String tenant, String name) {
        ProjectDocument p = new ProjectDocument();
        p.setTenantId(tenant);
        p.setName(name);
        return p;
    }

    private static DocumentDocument inlineDoc(
            String id, String path, String title, String content) {
        return DocumentDocument.builder()
                .id(id)
                .path(path)
                .title(title)
                .inlineText(content)
                .build();
    }

    private static Map<String, Object> reply(String summary, List<String> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(DocumentSummaryDriver.FIELD_SUMMARY, summary);
        m.put(DocumentSummaryDriver.FIELD_TAGS, tags);
        return m;
    }
}
