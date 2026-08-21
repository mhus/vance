package de.mhus.vance.shared.starred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Behaviour of the starred store. The recurring theme: the server owns four
 * fields of an entry and must leave everything else exactly as the person wrote
 * it — across a re-star, an unstar and a reconcile.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StarredServiceTest {

    private static final String TENANT = "acme";
    private static final String USER = "mhu";
    private static final String HUB = "_user_mhu";

    @Mock private DocumentService documentService;
    @Mock private PermissionService permissionService;

    private StarredService service;

    @BeforeEach
    void setUp() {
        service = new StarredService(documentService, permissionService);
        when(permissionService.check(any(), any(), any())).thenReturn(true);
    }

    // ── read ────────────────────────────────────────────────────────

    @Test
    void load_missingFile_isAnEmptyListNotAnError() {
        givenNoControlFile();

        assertThat(service.load(TENANT, USER).items()).isEmpty();
    }

    @Test
    void load_brokenEntry_isSkippedSoTheRestStillWorks() {
        givenControlFile("""
                items:
                  - project: p
                  - project: p
                    path: good.md
                    kind: text
                """);

        assertThat(service.load(TENANT, USER).items())
                .extracting(StarredItem::path)
                .containsExactly("good.md");
    }

    @Test
    void listDisplayed_dropsHiddenAndDisabled() {
        givenControlFile(threeStates());

        assertThat(service.listDisplayed(TENANT, USER))
                .extracting(StarredItem::path)
                .containsExactly("visible.md");
    }

    @Test
    void listResolvable_keepsHiddenButNotDisabled() {
        givenControlFile(threeStates());

        assertThat(service.listResolvable(TENANT, USER))
                .extracting(StarredItem::path)
                .containsExactly("visible.md", "hidden.md");
    }

    @Test
    void findByType_returnsHiddenEntry() {
        // The whole point of `hidden`: out of sight, still the answer.
        givenControlFile("""
                items:
                  - project: p
                    path: links/_app.yaml
                    kind: application
                    type: links
                    hidden: true
                """);

        assertThat(service.findByType(TENANT, USER, "links"))
                .get()
                .extracting(StarredItem::path)
                .isEqualTo("links/_app.yaml");
    }

    @Test
    void findByType_ignoresDisabledEntry() {
        givenControlFile("""
                items:
                  - project: p
                    path: links/_app.yaml
                    kind: application
                    type: links
                    enabled: false
                """);

        assertThat(service.findByType(TENANT, USER, "links")).isEmpty();
    }

    @Test
    void findByType_tieBreakIsFileOrderNotHighlight() {
        // highlight is purely visual — letting it decide would turn an emphasis
        // into a target selection.
        givenControlFile("""
                items:
                  - project: p
                    path: first/_app.yaml
                    kind: application
                    type: links
                  - project: p
                    path: second/_app.yaml
                    kind: application
                    type: links
                    highlight: true
                """);

        assertThat(service.findByType(TENANT, USER, "links"))
                .get()
                .extracting(StarredItem::path)
                .isEqualTo("first/_app.yaml");
    }

    @Test
    void listByKind_filtersOnDocumentForm() {
        givenControlFile("""
                items:
                  - project: p
                    path: a.md
                    kind: workpage
                  - project: p
                    path: b/_app.yaml
                    kind: application
                    type: links
                """);

        assertThat(service.listByKind(TENANT, USER, "workpage"))
                .extracting(StarredItem::path)
                .containsExactly("a.md");
    }

    // ── star ────────────────────────────────────────────────────────

    @Test
    void star_takesKindAndTypeFromTheLiveDocument() {
        givenNoControlFile();
        givenTarget("work", "links/_app.yaml", "application", "links", "Link list");

        StarredItem item = service.star(TENANT, USER, "work", "links/_app.yaml",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.kind()).isEqualTo("application");
        assertThat(item.type()).isEqualTo("links");
        assertThat(item.title()).isEqualTo("Link list");
        assertThat(written()).contains("type: links");
    }

    @Test
    void star_documentWithoutKind_fallsBackToText() {
        givenNoControlFile();
        givenTarget("work", "notes/today.md", null, null, "Today");

        StarredItem item = service.star(TENANT, USER, "work", "notes/today.md",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.kind()).isEqualTo("text");
        assertThat(item.type()).isNull();
    }

    @Test
    void star_application_takesTheTitleFromTheManifest() {
        // The file stem of every app manifest is "_app.yaml", so the stem
        // fallback would label every app identically.
        givenNoControlFile();
        DocumentDocument target = givenTarget(
                "work", "apps/links1/_app.yaml", "application", "links", null);
        when(documentService.readContent(target)).thenReturn("""
                $meta:
                  kind: application
                  app: links
                title: My reading list
                """);

        StarredItem item = service.star(TENANT, USER, "work", "apps/links1/_app.yaml",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("My reading list");
    }

    @Test
    void star_applicationWithoutManifestTitle_fallsBackToTheFolder() {
        givenNoControlFile();
        DocumentDocument target = givenTarget(
                "work", "apps/links1/_app.yaml", "application", "links", null);
        when(documentService.readContent(target)).thenReturn("""
                $meta:
                  kind: application
                  app: links
                """);

        StarredItem item = service.star(TENANT, USER, "work", "apps/links1/_app.yaml",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("links1");
    }

    @Test
    void star_applicationWithUnreadableManifest_stillGetsAUsefulLabel() {
        givenNoControlFile();
        DocumentDocument target = givenTarget(
                "work", "apps/links1/_app.yaml", "application", "links", null);
        when(documentService.readContent(target)).thenThrow(new IllegalStateException("gone"));

        StarredItem item = service.star(TENANT, USER, "work", "apps/links1/_app.yaml",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("links1");
    }

    @Test
    void star_plainDocument_keepsUsingTheFileStem() {
        givenNoControlFile();
        givenTarget("work", "notes/today.md", null, null, null);

        StarredItem item = service.star(TENANT, USER, "work", "notes/today.md",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("today.md");
    }

    @Test
    void star_enforcesReadOnTheTarget() {
        givenNoControlFile();
        givenTarget("work", "a.md", "text", null, "A");

        service.star(TENANT, USER, "work", "a.md",
                null, null, null, null, SecurityContext.SYSTEM);

        verify(permissionService).enforce(
                eq(SecurityContext.SYSTEM),
                eq(new Resource.Document(TENANT, "work", "a.md")),
                eq(Action.READ));
    }

    @Test
    void star_missingTarget_throws() {
        givenNoControlFile();
        when(documentService.findByPath(TENANT, "work", "gone.md")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.star(TENANT, USER, "work", "gone.md",
                null, null, null, null, SecurityContext.SYSTEM))
                .isInstanceOf(StarredService.StarredException.class);
    }

    @Test
    void star_reStar_preservesAuthoredFieldsAndUnknownKeys() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                    title: My own title
                    description: why I keep this
                    highlight: true
                    somethingNew: keep-me
                """);
        givenTarget("work", "a.md", "text", null, "Server title");

        StarredItem item = service.star(TENANT, USER, "work", "a.md",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("My own title");
        assertThat(item.description()).isEqualTo("why I keep this");
        assertThat(item.highlight()).isTrue();
        assertThat(item.extra()).containsEntry("somethingNew", "keep-me");
    }

    @Test
    void star_reStarOfDisabledEntry_switchesItBackOn() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                    description: kept
                    enabled: false
                """);
        givenTarget("work", "a.md", "text", null, "A");

        StarredItem item = service.star(TENANT, USER, "work", "a.md",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(item.enabled()).isTrue();
        assertThat(item.description()).isEqualTo("kept");
    }

    @Test
    void star_explicitTitle_overwritesTheStoredOne() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                    title: old
                """);
        givenTarget("work", "a.md", "text", null, "A");

        StarredItem item = service.star(TENANT, USER, "work", "a.md",
                "new", null, null, null, SecurityContext.SYSTEM);

        assertThat(item.title()).isEqualTo("new");
    }

    @Test
    void star_existingEntry_keepsItsPositionInTheList() {
        givenControlFile("""
                items:
                  - project: work
                    path: first.md
                    kind: text
                  - project: work
                    path: second.md
                    kind: text
                """);
        givenTarget("work", "first.md", "text", null, "First");

        service.star(TENANT, USER, "work", "first.md",
                null, null, null, null, SecurityContext.SYSTEM);

        assertThat(StarredCodec.parseLenient(written()).items())
                .extracting(StarredItem::path)
                .containsExactly("first.md", "second.md");
    }

    // ── unstar ──────────────────────────────────────────────────────

    @Test
    void unstar_plainEntry_isRemoved() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                    title: A
                """);

        assertThat(service.unstar(TENANT, USER, "work", "a.md", SecurityContext.SYSTEM)).isTrue();
        assertThat(StarredCodec.parseLenient(written()).items()).isEmpty();
    }

    @Test
    void unstar_entryWithAuthoredContent_isOnlySwitchedOff() {
        // A mis-click must not eat a typed description.
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                    description: why I keep this
                """);

        service.unstar(TENANT, USER, "work", "a.md", SecurityContext.SYSTEM);

        assertThat(StarredCodec.parseLenient(written()).items()).singleElement()
                .satisfies(i -> {
                    assertThat(i.enabled()).isFalse();
                    assertThat(i.description()).isEqualTo("why I keep this");
                });
    }

    @Test
    void unstar_unknownEntry_changesNothing() {
        givenControlFile("items: []");

        assertThat(service.unstar(TENANT, USER, "work", "a.md", SecurityContext.SYSTEM)).isFalse();
        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    // ── setHidden ───────────────────────────────────────────────────

    @Test
    void setHidden_movesBetweenVisibleAndHiddenWithoutUnregistering() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                """);

        assertThat(service.setHidden(TENANT, USER, "work", "a.md", true, SecurityContext.SYSTEM))
                .isTrue();

        StarredItem item = StarredCodec.parseLenient(written()).items().get(0);
        assertThat(item.visibility()).isEqualTo(StarredVisibility.HIDDEN);
        assertThat(item.enabled()).isTrue();
    }

    @Test
    void setHidden_noChange_doesNotWrite() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                """);

        assertThat(service.setHidden(TENANT, USER, "work", "a.md", false, SecurityContext.SYSTEM))
                .isTrue();
        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    // ── reconcile ───────────────────────────────────────────────────

    @Test
    void reconcile_driftedKind_isRefreshed() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                """);
        givenTarget("work", "a.md", "workpage", null, "A");

        StarredService.ReconcileResult result =
                service.reconcile(TENANT, USER, SecurityContext.SYSTEM);

        assertThat(result.changed()).isTrue();
        assertThat(result.entries()).singleElement()
                .satisfies(e -> assertThat(e.outcome())
                        .isEqualTo(StarredService.ReconcileOutcome.REFRESHED));
        assertThat(StarredCodec.parseLenient(written()).items()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo("workpage"));
    }

    @Test
    void reconcile_missingTarget_isReportedButNotDeleted() {
        // Removing a curated entry over what may be a transient failure is the
        // user's call, not the server's.
        givenControlFile("""
                items:
                  - project: work
                    path: gone.md
                    kind: text
                """);
        when(documentService.findByPath(TENANT, "work", "gone.md")).thenReturn(Optional.empty());

        StarredService.ReconcileResult result =
                service.reconcile(TENANT, USER, SecurityContext.SYSTEM);

        assertThat(result.changed()).isFalse();
        assertThat(result.entries()).singleElement()
                .satisfies(e -> assertThat(e.outcome())
                        .isEqualTo(StarredService.ReconcileOutcome.MISSING));
        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reconcile_unreadableTarget_isReportedAsForbidden() {
        givenControlFile("""
                items:
                  - project: secret
                    path: a.md
                    kind: text
                """);
        givenTarget("secret", "a.md", "text", null, "A");
        when(permissionService.check(any(),
                eq(new Resource.Document(TENANT, "secret", "a.md")), eq(Action.READ)))
                .thenReturn(false);

        StarredService.ReconcileResult result =
                service.reconcile(TENANT, USER, SecurityContext.SYSTEM);

        assertThat(result.entries()).singleElement()
                .satisfies(e -> assertThat(e.outcome())
                        .isEqualTo(StarredService.ReconcileOutcome.FORBIDDEN));
    }

    @Test
    void reconcile_everythingUpToDate_writesNothing() {
        givenControlFile("""
                items:
                  - project: work
                    path: a.md
                    kind: text
                """);
        givenTarget("work", "a.md", "text", null, "A");

        StarredService.ReconcileResult result =
                service.reconcile(TENANT, USER, SecurityContext.SYSTEM);

        assertThat(result.changed()).isFalse();
        assertThat(result.entries()).singleElement()
                .satisfies(e -> assertThat(e.outcome())
                        .isEqualTo(StarredService.ReconcileOutcome.OK));
        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    // ── fixtures ────────────────────────────────────────────────────

    private static String threeStates() {
        return """
                items:
                  - project: p
                    path: visible.md
                    kind: text
                  - project: p
                    path: hidden.md
                    kind: text
                    hidden: true
                  - project: p
                    path: off.md
                    kind: text
                    enabled: false
                """;
    }

    private void givenNoControlFile() {
        when(documentService.findByPath(TENANT, HUB, StarredService.DOC_PATH))
                .thenReturn(Optional.empty());
    }

    private void givenControlFile(String yaml) {
        DocumentDocument doc = new DocumentDocument();
        doc.setPath(StarredService.DOC_PATH);
        when(documentService.findByPath(TENANT, HUB, StarredService.DOC_PATH))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }

    private DocumentDocument givenTarget(
            String project, String path, String kind, String app, String title) {
        DocumentDocument doc = new DocumentDocument();
        doc.setPath(path);
        doc.setKind(kind);
        doc.setTitle(title);
        doc.setMimeType("application/yaml");
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (app != null) headers.put("app", app);
        doc.setHeaders(headers);
        when(documentService.findByPath(TENANT, project, path)).thenReturn(Optional.of(doc));
        return doc;
    }

    /** The YAML the service last persisted. */
    private String written() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq(TENANT), eq(HUB), eq(StarredService.DOC_PATH), any(),
                anyList(), body.capture(), any(), any());
        return body.getValue();
    }
}
