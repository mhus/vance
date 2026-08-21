package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * Unit tests for {@link AppShareHandler}. What matters: the candidate list is
 * the apps that <em>say</em> they take this (not a name list here), the write is
 * authorized against the app's own project, "already there" is not a refusal,
 * and one broken app does not hide the others.
 */
class AppShareHandlerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());

    private StarredService starredService;
    private PermissionService permissionService;
    private final List<VanceApplication> apps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        starredService = mock(StarredService.class);
        permissionService = mock(PermissionService.class);
        apps.clear();
    }

    private AppShareHandler handler() {
        return new AppShareHandler(starredService,
                new VanceApplicationRegistry(List.copyOf(apps)), permissionService);
    }

    // ── Availability ───────────────────────────────────────────────

    @Test
    void availability_nothingStarred_saysSo() {
        givenStarred();

        ShareAvailability availability = handler().availability(linkScope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("No app in your starred list");
    }

    @Test
    void availability_starredAppThatRefusesThisSubject_saysSoDifferently() {
        apps.add(new FakeApp("links", intake -> intake.link() != null));
        givenStarred(starred("reading", "bookmarks/_app.yaml", "links", "Lesezeichen", false));

        ShareAvailability availability = handler().availability(documentScope());

        // Two reasons, told apart: nothing starred is a different thing to fix
        // than nothing starred that can take *this*.
        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("takes this");
    }

    @Test
    void availability_anAppThatAcceptsIt_isReady() {
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        assertThat(handler().availability(documentScope()).available()).isTrue();
    }

    @Test
    void availability_starredAppWithNoBeanBehindIt_isNotACandidate() {
        // An addon that is not deployed leaves the starred entry in the file.
        givenStarred(starred("plan", "todo/_app.yaml", "kanban", "Board", false));

        assertThat(handler().availability(linkScope()).available()).isFalse();
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_oneCandidate_asksOnlyForANote() {
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        List<FormFieldDto> fields = handler().form(linkScope());

        // One app needs no question — the same cut the smtp handler makes for a
        // single pack.
        assertThat(fields).extracting(FormFieldDto::getName)
                .containsExactly(AppShareHandler.FIELD_NOTE);
    }

    @Test
    void form_severalCandidates_offersTheAppFirst() {
        apps.add(new FakeApp("gtd", intake -> true));
        apps.add(new FakeApp("issues", intake -> true));
        givenStarred(
                starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false),
                starred(PROJECT, "bugs/_app.yaml", "issues", "Fehler", false));

        List<FormFieldDto> fields = handler().form(linkScope());

        assertThat(fields).extracting(FormFieldDto::getName)
                .containsExactly(AppShareHandler.FIELD_APP, AppShareHandler.FIELD_NOTE);
        assertThat(fields.get(0).getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly("plan|todo/_app.yaml", PROJECT + "|bugs/_app.yaml");
    }

    @Test
    void form_onlyAcceptingAppsAreOffered() {
        apps.add(new FakeApp("links", intake -> intake.link() != null));
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(
                starred("reading", "bookmarks/_app.yaml", "links", "Lesezeichen", false),
                starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        // A document share has no URL — the links app refuses it, GTD does not.
        List<FormFieldDto> fields = handler().form(documentScope());

        assertThat(fields).extracting(FormFieldDto::getName)
                .containsExactly(AppShareHandler.FIELD_NOTE);
    }

    @Test
    void form_highlightedAppComesFirst() {
        apps.add(new FakeApp("gtd", intake -> true));
        apps.add(new FakeApp("issues", intake -> true));
        givenStarred(
                starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false),
                starred(PROJECT, "bugs/_app.yaml", "issues", "Fehler", true));

        FormFieldDto app = handler().form(linkScope()).get(0);

        // StarredService returns file order and refuses to let highlight pick a
        // target; ordering a list shown to a human is a different question.
        assertThat(app.getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly(PROJECT + "|bugs/_app.yaml", "plan|todo/_app.yaml");
        assertThat(app.getDefaultValue()).isEqualTo(PROJECT + "|bugs/_app.yaml");
    }

    @Test
    void form_appInAnotherProject_carriesTheProjectInItsLabel() {
        apps.add(new FakeApp("gtd", intake -> true));
        apps.add(new FakeApp("issues", intake -> true));
        givenStarred(
                starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false),
                starred(PROJECT, "bugs/_app.yaml", "issues", "Fehler", false));

        List<FormChoiceDto> choices = handler().form(linkScope()).get(0).getChoices();

        assertThat(choices).extracting(c -> c.getLabel().values().iterator().next())
                .containsExactly("Aufgaben (plan)", "Fehler");
    }

    @Test
    void form_anAppThatThrowsWhileAnswering_isSkippedNotFatal() {
        apps.add(new FakeApp("broken", intake -> {
            throw new IllegalStateException("boom");
        }));
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(
                starred("plan", "broken/_app.yaml", "broken", "Kaputt", false),
                starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        // One broken app must not hide the others.
        assertThat(handler().form(linkScope())).extracting(FormFieldDto::getName)
                .containsExactly(AppShareHandler.FIELD_NOTE);
    }

    // ── Share ──────────────────────────────────────────────────────

    @Test
    void share_handsTheSubjectAndTheNoteToTheApp() {
        FakeApp gtd = new FakeApp("gtd", intake -> true);
        apps.add(gtd);
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        ShareResult result = handler().share(new ShareRequest(linkScope(),
                Map.of("note", "passt zu unserem Thema")));

        VanceApplication.ShareIntakeContext seen = gtd.lastContext;
        assertThat(seen).isNotNull();
        assertThat(seen.tenantId()).isEqualTo(TENANT);
        assertThat(seen.projectName()).isEqualTo("plan");
        // The manifest name is stripped: an app works on its folder.
        assertThat(seen.folder()).isEqualTo("todo");
        assertThat(seen.intake().title()).isEqualTo("Canyon test results");
        assertThat(seen.intake().link()).isEqualTo("https://example.com/hit");
        assertThat(seen.note()).isEqualTo("passt zu unserem Thema");
        assertThat(seen.userId()).isEqualTo("mara");
        assertThat(result.message()).isEqualTo("Added to Aufgaben");
        assertThat(result.details()).containsEntry("created", true);
        assertThat(result.details()).containsEntry("appType", "gtd");
    }

    @Test
    void share_thingAlreadyThere_isSuccessWithAQualifyingMessage() {
        FakeApp gtd = new FakeApp("gtd", intake -> true);
        gtd.created = false;
        apps.add(gtd);
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        ShareResult result = handler().share(new ShareRequest(linkScope(), Map.of()));

        // Nothing is broken, it is just there. Saying "added" would be a lie;
        // a ShareException would be one too.
        assertThat(result.message()).isEqualTo("Already in Aufgaben");
        assertThat(result.details()).containsEntry("created", false);
    }

    @Test
    void share_authorizesTheAppsProjectNotTheSharesProject() {
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        handler().share(new ShareRequest(linkScope(), Map.of()));

        // The starred list is per user across projects, so the app can live
        // somewhere else than the share does.
        verify(permissionService).enforce(
                MARA, new Resource.Project(TENANT, "plan"), Action.WRITE);
    }

    @Test
    void share_withoutWriteOnTheAppsProject_isDeniedBeforeTheAppIsCalled() {
        FakeApp gtd = new FakeApp("gtd", intake -> true);
        apps.add(gtd);
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));
        doThrow(new PermissionDeniedException(
                MARA, new Resource.Project(TENANT, "plan"), Action.WRITE))
                .when(permissionService).enforce(any(SecurityContext.class),
                        any(Resource.class), eq(Action.WRITE));

        assertThatThrownBy(() -> handler().share(new ShareRequest(linkScope(), Map.of())))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(gtd.lastContext).isNull();
    }

    @Test
    void share_appTheFormNeverOffered_isRefused() {
        apps.add(new FakeApp("gtd", intake -> true));
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        // Otherwise the form would be a way to name an arbitrary project.
        assertThatThrownBy(() -> handler().share(new ShareRequest(linkScope(),
                Map.of("app", "secret-project|anywhere/_app.yaml"))))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("secret-project");

        verify(permissionService, never()).enforce(any(SecurityContext.class),
                any(Resource.class), any());
    }

    @Test
    void share_anAppThatRefusesTheSubject_isNotAValidTarget() {
        apps.add(new FakeApp("links", intake -> intake.link() != null));
        givenStarred(starred("reading", "bookmarks/_app.yaml", "links", "Lesezeichen", false));

        assertThatThrownBy(() -> handler().share(new ShareRequest(documentScope(), Map.of())))
                .isInstanceOf(ShareException.class);
    }

    // ── The body the apps get ──────────────────────────────────────

    @Test
    void body_putsTheRemarkFirstAndQuotesTheSnippet() {
        FakeApp gtd = new FakeApp("gtd", intake -> true);
        apps.add(gtd);
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        handler().share(new ShareRequest(linkScope(), Map.of("note", "ansehen")));

        // The remark leads because it is the one sentence a person wrote; the
        // snippet is quoted because it describes the page, not the sharer.
        assertThat(gtd.lastContext.body())
                .isEqualTo("ansehen\n\nhttps://example.com/hit\n\n> …the test is done…");
    }

    @Test
    void body_withoutARemark_startsWithTheLink() {
        FakeApp gtd = new FakeApp("gtd", intake -> true);
        apps.add(gtd);
        givenStarred(starred("plan", "todo/_app.yaml", "gtd", "Aufgaben", false));

        handler().share(new ShareRequest(linkScope(), Map.of()));

        assertThat(gtd.lastContext.body())
                .isEqualTo("https://example.com/hit\n\n> …the test is done…");
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenStarred(StarredItem... items) {
        when(starredService.listResolvable(TENANT, "mara")).thenReturn(List.of(items));
    }

    private static StarredItem starred(String project, String path, String type,
                                       String title, boolean highlight) {
        return new StarredItem(project, path, "application", type,
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

    /**
     * A hand-written stub rather than a Mockito mock: the point of these tests
     * is that the <em>app</em> decides, so the app's answer has to be real code
     * with the intake in its hands.
     */
    private static final class FakeApp implements VanceApplication {

        private final String name;
        private final java.util.function.Predicate<ShareIntake> accepts;
        boolean created = true;
        @Nullable ShareIntakeContext lastContext;

        FakeApp(String name, java.util.function.Predicate<ShareIntake> accepts) {
            this.name = name;
            this.accepts = accepts;
        }

        @Override
        public String appName() {
            return name;
        }

        @Override
        public RefreshResult refresh(RefreshContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean acceptsShare(ShareIntake intake) {
            return accepts.test(intake);
        }

        @Override
        public ShareIntakeResult acceptShare(ShareIntakeContext ctx) {
            lastContext = ctx;
            return new ShareIntakeResult(created, "Aufgaben");
        }
    }
}
