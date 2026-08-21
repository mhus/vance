package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.milliways.ShareAvailability;
import de.mhus.vance.brain.milliways.ShareException;
import de.mhus.vance.brain.milliways.ShareRequest;
import de.mhus.vance.brain.milliways.ShareResult;
import de.mhus.vance.brain.milliways.ShareScope;
import de.mhus.vance.brain.milliways.ShareSubject;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LinksShareHandler}. What matters here: url and title
 * come from the subject while teaser and image stay empty, the write is
 * authorized against the <em>app's</em> project, "already there" is not a
 * refusal, and availability reads the subject.
 */
class LinksShareHandlerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());

    private StarredService starredService;
    private LinksManifestOps manifestOps;
    private LinksStore store;
    private PermissionService permissionService;
    private LinksShareHandler handler;

    @BeforeEach
    void setUp() {
        starredService = mock(StarredService.class);
        manifestOps = mock(LinksManifestOps.class);
        store = mock(LinksStore.class);
        permissionService = mock(PermissionService.class);
        handler = new LinksShareHandler(starredService, manifestOps, store, permissionService);
        when(manifestOps.addEntry(anyString(), anyString(), anyString(), anyString(),
                any(), any(LinksManifestOps.Position.class), any()))
                .thenReturn(true);
    }

    // ── Availability ───────────────────────────────────────────────

    @Test
    void availability_subjectWithoutLink_isUnavailable() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));

        ShareAvailability availability = handler.availability(documentScope());

        // The subject decides, not the project: a document share has no URL,
        // and a vance: entry would be a card with no preview.
        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("no link");
    }

    @Test
    void availability_noStarredLinksApp_isUnavailable() {
        givenApps();

        assertThat(handler.availability(linkScope()).available()).isFalse();
    }

    @Test
    void availability_linkAndApp_isReady() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));

        assertThat(handler.availability(linkScope()).available()).isTrue();
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_oneApp_asksNothingAboutTheAppAndOffersItsGroups() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of("Rust", "Vue"));

        List<FormFieldDto> fields = handler.form(linkScope());

        assertThat(fields).extracting(FormFieldDto::getName)
                .containsExactly(LinksShareHandler.FIELD_GROUP,
                        LinksShareHandler.FIELD_POSITION, LinksShareHandler.FIELD_NOTE);
        FormFieldDto group = fields.get(0);
        assertThat(group.getType()).isEqualTo("select");
        assertThat(group.getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly("", "Rust", "Vue");
    }

    @Test
    void form_severalApps_offersTheAppAndFallsBackToAFreeTextGroup() {
        givenApps(
                app("reading", "bookmarks/_app.yaml", "Lesezeichen", false),
                app(PROJECT, "refs/_app.yaml", "Referenzen", false));

        List<FormFieldDto> fields = handler.form(linkScope());

        assertThat(fields.get(0).getName()).isEqualTo(LinksShareHandler.FIELD_APP);
        // The group list belongs to an app that has not been picked yet, and
        // dependent choices are not part of the form grammar.
        assertThat(fields.get(1).getName()).isEqualTo(LinksShareHandler.FIELD_GROUP);
        assertThat(fields.get(1).getType()).isEqualTo("string");
    }

    @Test
    void form_highlightedAppComesFirst() {
        givenApps(
                app("reading", "bookmarks/_app.yaml", "Lesezeichen", false),
                app(PROJECT, "refs/_app.yaml", "Referenzen", true));

        FormFieldDto app = handler.form(linkScope()).get(0);

        // StarredService returns file order and refuses to let highlight pick a
        // target; ordering a list shown to a human is a different question.
        assertThat(app.getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly(PROJECT + "|refs/_app.yaml", "reading|bookmarks/_app.yaml");
        assertThat(app.getDefaultValue()).isEqualTo(PROJECT + "|refs/_app.yaml");
    }

    @Test
    void form_appInAnotherProject_carriesTheProjectInItsLabel() {
        givenApps(
                app("reading", "bookmarks/_app.yaml", "Lesezeichen", false),
                app(PROJECT, "refs/_app.yaml", "Referenzen", false));

        List<FormChoiceDto> choices = handler.form(linkScope()).get(0).getChoices();

        assertThat(choices).extracting(c -> c.getLabel().values().iterator().next())
                .containsExactly("Lesezeichen (reading)", "Referenzen");
    }

    @Test
    void form_unreadableManifest_degradesToNoGroup() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        when(store.load(TENANT, "reading", "bookmarks")).thenThrow(new RuntimeException("gone"));

        // A broken manifest must not produce a broken form; the share itself
        // will report the real problem.
        assertThat(handler.form(linkScope()).get(0).getChoices())
                .extracting(FormChoiceDto::getValue).containsExactly("");
    }

    // ── Share ──────────────────────────────────────────────────────

    @Test
    void share_writesUrlAndTitleFromTheSubjectAndLeavesTeaserEmpty() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of("Rust"));

        ShareResult result = handler.share(new ShareRequest(linkScope(),
                Map.of("group", "Rust", "position", "top", "note", "passt zu unserem Thema")));

        ArgumentCaptor<LinksManifestOps.LinkFields> fields =
                ArgumentCaptor.forClass(LinksManifestOps.LinkFields.class);
        verify(manifestOps).addEntry(eq(TENANT), eq("reading"), eq("bookmarks"),
                eq("https://example.com/hit"), fields.capture(),
                eq(LinksManifestOps.Position.TOP), eq("mara"));
        assertThat(fields.getValue().title()).isEqualTo("Canyon test results");
        assertThat(fields.getValue().note()).isEqualTo("passt zu unserem Thema");
        assertThat(fields.getValue().group()).isEqualTo("Rust");
        // The snippet describes the page — exactly what an empty teaser leaves
        // to the preview proxy. Storing it would be the stale copy.
        assertThat(fields.getValue().teaser()).isNull();
        assertThat(fields.getValue().image()).isNull();
        assertThat(fields.getValue().tags()).isNull();
        assertThat(result.message()).isEqualTo("Added to Lesezeichen");
        assertThat(result.details()).containsEntry("added", true);
    }

    @Test
    void share_withoutAPosition_addsAtTheBottom() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of());

        handler.share(new ShareRequest(linkScope(), Map.of()));

        verify(manifestOps).addEntry(anyString(), anyString(), anyString(), anyString(),
                any(), eq(LinksManifestOps.Position.BOTTOM), any());
    }

    @Test
    void share_urlAlreadyInTheList_isSuccessWithAQualifyingMessage() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of());
        when(manifestOps.addEntry(anyString(), anyString(), anyString(), anyString(),
                any(), any(LinksManifestOps.Position.class), any()))
                .thenReturn(false);

        ShareResult result = handler.share(new ShareRequest(linkScope(), Map.of()));

        // Nothing is broken, it is just there. Saying "added" would be a lie
        // about the state of the list; a ShareException would be one too.
        assertThat(result.message()).isEqualTo("Already in Lesezeichen");
        assertThat(result.details()).containsEntry("added", false);
    }

    @Test
    void share_authorizesTheAppsProjectNotTheSharesProject() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of());

        handler.share(new ShareRequest(linkScope(), Map.of()));

        // The starred list is per user across projects, so the app can live
        // somewhere else than the share does.
        verify(permissionService).enforce(
                MARA, new Resource.Project(TENANT, "reading"), Action.WRITE);
    }

    @Test
    void share_withoutWriteOnTheAppsProject_isDenied() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));
        givenGroups("reading", "bookmarks", List.of());
        doThrow(new PermissionDeniedException(
                MARA, new Resource.Project(TENANT, "reading"), Action.WRITE))
                .when(permissionService).enforce(any(SecurityContext.class),
                        any(Resource.class), eq(Action.WRITE));

        assertThatThrownBy(() -> handler.share(new ShareRequest(linkScope(), Map.of())))
                .isInstanceOf(PermissionDeniedException.class);

        verify(manifestOps, never()).addEntry(anyString(), anyString(), anyString(), anyString(),
                any(), any(LinksManifestOps.Position.class), any());
    }

    @Test
    void share_appTheFormNeverOffered_isRefused() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));

        // Otherwise the form would be a way to name an arbitrary project.
        assertThatThrownBy(() -> handler.share(new ShareRequest(linkScope(),
                Map.of("app", "secret-project|anywhere/_app.yaml"))))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("secret-project");

        verify(permissionService, never()).enforce(any(SecurityContext.class),
                any(Resource.class), any());
    }

    @Test
    void share_subjectWithoutLink_isRefused() {
        givenApps(app("reading", "bookmarks/_app.yaml", "Lesezeichen", false));

        assertThatThrownBy(() -> handler.share(new ShareRequest(documentScope(), Map.of())))
                .isInstanceOf(ShareException.class);
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenApps(StarredItem... apps) {
        when(starredService.listByType(TENANT, "mara", LinksApplication.APP_NAME))
                .thenReturn(List.of(apps));
    }

    private void givenGroups(String project, String folder, List<String> groups) {
        when(store.load(TENANT, project, folder)).thenReturn(new LinksStore.Loaded(
                folder, null, null, new LinksConfig(groups, List.of(), LinksConfig.DEFAULT_INDEX)));
    }

    private static StarredItem app(String project, String path, String title, boolean highlight) {
        return new StarredItem(project, path, "application", LinksApplication.APP_NAME,
                title, null, highlight, true, false, Map.of());
    }

    private static ShareScope linkScope() {
        return new ShareScope(MARA, TENANT, PROJECT,
                new ShareSubject("Canyon test results", "https://example.com/hit",
                        "…the test is done…", null),
                null);
    }

    private static ShareScope documentScope() {
        return new ShareScope(MARA, TENANT, PROJECT,
                ShareSubject.ofDocument(DocumentRef.of(PROJECT, "notes/results.md")),
                null);
    }
}
