package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four doc tools that shipped with id-only addressing —
 * {@code doc_lock_set}/{@code _add}/{@code _remove} and
 * {@code doc_set_summary} — now take the family-standard selector
 * ({@code path} | {@code id}, plus {@code projectId}).
 *
 * <p>Before this, a caller who knew the path — which is every caller, because
 * paths are what the LLM works with — had to fetch a Mongo id via
 * {@code doc_find}/{@code doc_info} first. Worst pair: {@code doc_summary}
 * reads by {@code path} while {@code doc_set_summary} wrote by
 * {@code documentId}, so a get/set round trip needed a third call in between.
 *
 * <p>{@code documentId} stays readable: prompts and saved calls that use it
 * must not break.
 */
class DocSelectorAddressingTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);
    private static final String DOC_ID = "doc-42";

    private DocumentService documentService;
    private KindToolSupport support;
    private SecurityContextFactory contextFactory;
    private DocumentDocument doc;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        support = mock(KindToolSupport.class);
        contextFactory = mock(SecurityContextFactory.class);

        doc = new DocumentDocument();
        doc.setId(DOC_ID);
        doc.setTenantId("acme");
        doc.setProjectId("proj-a");
        doc.setPath("documents/notes.md");

        // The shared resolver is what handles path | id | documentId; these
        // tests assert that the tools go through it and use what it returns.
        when(support.loadDocument(any(), eq(CTX))).thenReturn(doc);
        when(documentService.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentService.setLockedFor(eq(DOC_ID), any(), any())).thenReturn(doc);
    }

    // ──────────────── schema ────────────────

    @Test
    void allFourDeclareTheStandardSelector_withDocumentIdAsAlias() {
        List<de.mhus.vance.toolpack.Tool> tools = List.of(
                new DocLockSetTool(documentService, contextFactory, support),
                new DocLockAddTool(documentService, contextFactory, support),
                new DocLockRemoveTool(documentService, contextFactory, support),
                new DocSetSummaryTool(documentService, contextFactory, support));

        for (de.mhus.vance.toolpack.Tool t : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props =
                    (Map<String, Object>) t.paramsSchema().get("properties");
            assertThat(props).as("%s selector", t.name())
                    .containsKeys("path", "id", "projectId", "documentId");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) t.paramsSchema().get("required");
            // documentId must not be required any more — path is a full
            // alternative, and "one of path/id" cannot be expressed as required.
            assertThat(required).as("%s required", t.name()).doesNotContain("documentId");
        }
    }

    // ──────────────── behaviour ────────────────

    @Test
    void lockSet_byPath_resolvesThroughTheSharedSelector() {
        Map<String, Object> out = new DocLockSetTool(documentService, contextFactory, support)
                .invoke(Map.of("path", "documents/notes.md", "lockedFor", List.of("AI")), CTX);

        assertThat(out).containsEntry("id", DOC_ID)
                .containsEntry("path", "documents/notes.md");
        assertThat(out.get("lockedFor")).isEqualTo(List.of("AI"));
    }

    @Test
    void lockSet_byDocumentId_stillWorks() {
        Map<String, Object> out = new DocLockSetTool(documentService, contextFactory, support)
                .invoke(Map.of("documentId", DOC_ID, "lockedFor", List.of("USER")), CTX);

        assertThat(out).containsEntry("id", DOC_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAdd_byPath_addsToTheExistingRoles() {
        doc.setLockedFor(Set.of(WriterRole.USER));

        Map<String, Object> out = new DocLockAddTool(documentService, contextFactory, support)
                .invoke(Map.of("path", "documents/notes.md", "role", "AI"), CTX);

        assertThat(out).containsEntry("id", DOC_ID);
        assertThat((List<String>) out.get("lockedFor")).containsExactlyInAnyOrder("AI", "USER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockRemove_byPath_dropsOnlyTheNamedRole() {
        doc.setLockedFor(Set.of(WriterRole.AI, WriterRole.USER));

        Map<String, Object> out = new DocLockRemoveTool(documentService, contextFactory, support)
                .invoke(Map.of("path", "documents/notes.md", "role", "AI"), CTX);

        assertThat((List<String>) out.get("lockedFor")).containsExactly("USER");
    }

    @Test
    void setSummary_byPath_closesTheGapToDocSummary() {
        // doc_summary reads by path; writing it back needed an id before.
        Map<String, Object> out = new DocSetSummaryTool(documentService, contextFactory, support)
                .invoke(Map.of("path", "documents/notes.md", "summary", "Short recap."), CTX);

        assertThat(out).containsEntry("id", DOC_ID)
                .containsEntry("path", "documents/notes.md")
                .containsEntry("summary", "Short recap.");
    }

    @Test
    void setSummary_blankSummary_clearsIt() {
        Map<String, Object> out = new DocSetSummaryTool(documentService, contextFactory, support)
                .invoke(Map.of("path", "documents/notes.md", "summary", ""), CTX);

        assertThat(out).containsEntry("id", DOC_ID).doesNotContainKey("summary");
    }

    // ──────────────── the alias mapping itself ────────────────

    @Test
    void withIdAlias_mapsDocumentIdOntoId_butNeverOverridesAnExplicitId() {
        assertThat(KindToolSupport.withIdAlias(Map.of("documentId", "a")))
                .containsEntry("id", "a");
        assertThat(KindToolSupport.withIdAlias(Map.of("id", "explicit", "documentId", "legacy")))
                .containsEntry("id", "explicit");
        // No selector at all stays untouched — loadDocument produces the error.
        assertThat(KindToolSupport.withIdAlias(Map.of("summary", "x")))
                .doesNotContainKey("id");
    }
}
