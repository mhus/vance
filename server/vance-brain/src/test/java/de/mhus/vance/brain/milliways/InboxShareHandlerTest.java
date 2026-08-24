package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link InboxShareHandler}. The two properties that matter:
 * the item is a <em>pointer</em> (never the document's content), and the
 * offered recipients are exactly those the sharer may deliver to — so the
 * form cannot offer someone the share would then refuse.
 */
class InboxShareHandlerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PATH = "notes/results.md";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());

    private UserService userService;
    private MaximegalonService inboxItemService;
    private PermissionService permissionService;
    private InboxShareHandler handler;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        inboxItemService = mock(MaximegalonService.class);
        permissionService = mock(PermissionService.class);
        handler = new InboxShareHandler(userService, inboxItemService, permissionService);
        when(permissionService.check(any(SecurityContext.class), any(Resource.class), any()))
                .thenReturn(true);
        when(userService.findByTenantAndName(TENANT, "mara"))
                .thenReturn(Optional.of(user("mara", "Mara Toon")));
    }

    // ── Availability ───────────────────────────────────────────────

    @Test
    void availability_sharerIsOnlyUser_isUnavailable() {
        when(userService.all(TENANT)).thenReturn(List.of(user("mara", "Mara Toon")));

        ShareAvailability availability = handler.availability(scope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).isNotBlank();
    }

    @Test
    void availability_onlyRecipientNotPermitted_isUnavailable() {
        when(userService.all(TENANT))
                .thenReturn(List.of(user("mara", "Mara Toon"), user("ford", "Ford Prefect")));
        when(permissionService.check(any(SecurityContext.class), any(Resource.class), any()))
                .thenReturn(false);

        assertThat(handler.availability(scope()).available()).isFalse();
    }

    @Test
    void availability_anotherHumanReachable_isReady() {
        when(userService.all(TENANT))
                .thenReturn(List.of(user("mara", "Mara Toon"), user("ford", "Ford Prefect")));

        assertThat(handler.availability(scope()).available()).isTrue();
    }

    @Test
    void availability_stopsAtTheFirstReachableUser() {
        // This runs on every GET /share/handlers — merely opening the menu —
        // and the question is a boolean, which the first hit answers. Checking
        // every user meant one authorization call per member of the tenant
        // before the menu could be drawn.
        when(userService.all(TENANT)).thenReturn(List.of(
                user("mara", "Mara Toon"),
                user("ford", "Ford Prefect"),
                user("zaphod", "Zaphod"),
                user("trillian", "Trillian")));

        assertThat(handler.availability(scope()).available()).isTrue();

        verify(permissionService, times(1))
                .check(any(SecurityContext.class), any(Resource.class), any());
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_offersOnlyDeliverableHumans() {
        UserDocument daemon = user("_daemon-01", "Daemon");
        daemon.setServiceAccount(true);
        UserDocument disabled = user("zaphod", "Zaphod");
        disabled.setStatus(UserStatus.DISABLED);
        UserDocument blocked = user("prostetnic", "Prostetnic Jeltz");
        when(userService.all(TENANT)).thenReturn(List.of(
                user("mara", "Mara Toon"), user("ford", "Ford Prefect"),
                daemon, disabled, blocked));
        when(permissionService.check(
                MARA,
                new Resource.InboxItem(TENANT, null, "prostetnic"),
                Action.WRITE))
                .thenReturn(false);

        List<FormFieldDto> fields = handler.form(scope());

        FormFieldDto recipients = fields.get(0);
        assertThat(recipients.getName()).isEqualTo(InboxShareHandler.FIELD_RECIPIENTS);
        assertThat(recipients.getType()).isEqualTo("multi_select");
        assertThat(recipients.getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly("ford");
    }

    @Test
    void form_choiceLabelDisambiguatesByUsername() {
        when(userService.all(TENANT))
                .thenReturn(List.of(user("mara", "Mara Toon"), user("ford", "Ford Prefect")));

        FormChoiceDto choice = handler.form(scope()).get(0).getChoices().get(0);

        assertThat(choice.getLabel().values()).containsExactly("Ford Prefect (ford)");
    }

    @Test
    void form_reasonFieldIsRequired() {
        when(userService.all(TENANT))
                .thenReturn(List.of(user("mara", "Mara Toon"), user("ford", "Ford Prefect")));

        FormFieldDto text = handler.form(scope()).get(1);

        assertThat(text.getName()).isEqualTo(InboxShareHandler.FIELD_TEXT);
        assertThat(text.getType()).isEqualTo("textarea");
        assertThat(text.isRequired()).isTrue();
    }

    // ── Share ──────────────────────────────────────────────────────

    @Test
    void share_writesPointerItem_notTheContent() {
        givenReachable("ford");

        handler.share(request(Map.of("recipients", List.of("ford"), "text", "test is done")));

        MaximegalonDocument item = captureItem();
        assertThat(item.getType()).isEqualTo(MaximegalonType.OUTPUT_DOCUMENT);
        assertThat(item.isRequiresAction()).isFalse();
        assertThat(item.getAssignedToUserId()).isEqualTo("ford");
        assertThat(item.getOriginatorUserId()).isEqualTo("mara");
        // displayTitle(), not the file name: the document has a title and
        // that is the more readable label. The path is in the documentRef.
        assertThat(item.getTitle()).isEqualTo("Mara Toon shared: Results");
        assertThat(item.getBody()).isEqualTo("test is done");
        assertThat(item.getTags()).containsExactly("share");
        // A typed field now, not a payload entry — "which threads are about this
        // document" is a query across every item type, and payload is
        // type-specific by contract.
        assertThat(item.getDocumentRef()).isNotNull();
        assertThat(item.getDocumentRef().getProjectId()).isEqualTo(PROJECT);
        assertThat(item.getDocumentRef().getPath()).isEqualTo(PATH);
        assertThat(item.getDocumentRef().getDocumentId()).isEqualTo("doc-1");
        assertThat(item.getPayload()).doesNotContainKey("documentRef");
        assertThat(item.getPayload()).doesNotContainKey("content");
    }

    @Test
    void share_multipleRecipients_getOneItemEach() {
        givenReachable("ford", "zaphod");

        ShareResult result = handler.share(request(
                Map.of("recipients", List.of("ford", "zaphod"), "text", "look")));

        verify(inboxItemService, times(2)).create(any(MaximegalonDocument.class));
        assertThat(result.details()).containsEntry("recipients", List.of("ford", "zaphod"));
        assertThat(result.message()).contains("2");
    }

    @Test
    void share_missingReason_isRefused() {
        givenReachable("ford");

        assertThatThrownBy(() -> handler.share(request(Map.of("recipients", List.of("ford")))))
                .isInstanceOf(ShareException.class);

        verify(inboxItemService, never()).create(any(MaximegalonDocument.class));
    }

    @Test
    void share_noRecipient_isRefused() {
        givenReachable("ford");

        assertThatThrownBy(() -> handler.share(request(Map.of("text", "look"))))
                .isInstanceOf(ShareException.class);
    }

    @Test
    void share_unreachableRecipient_doesNotCancelTheOthers() {
        givenReachable("ford");

        ShareResult result = handler.share(request(
                Map.of("recipients", List.of("ford", "trillian"), "text", "look")));

        verify(inboxItemService, times(1)).create(any(MaximegalonDocument.class));
        assertThat(result.details()).containsEntry("recipients", List.of("ford"));
        assertThat(result.details()).containsEntry("rejected", List.of("trillian"));
        assertThat(result.message()).contains("trillian");
    }

    @Test
    void share_everyRecipientUnreachable_isRefused() {
        givenReachable("ford");

        assertThatThrownBy(() -> handler.share(request(
                Map.of("recipients", List.of("trillian"), "text", "look"))))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("trillian");

        verify(inboxItemService, never()).create(any(MaximegalonDocument.class));
    }

    @Test
    void share_sharerWithoutTitle_titleFallsBackToUsername() {
        givenReachable("ford");
        when(userService.findByTenantAndName(TENANT, "mara"))
                .thenReturn(Optional.of(user("mara", null)));

        handler.share(request(Map.of("recipients", List.of("ford"), "text", "look")));

        assertThat(captureItem().getTitle()).isEqualTo("mara shared: Results");
    }

    // ── Subject projection ─────────────────────────────────────────

    @Test
    void share_linkAndSnippetOnly_writesOutputTextWithBothInThePayload() {
        givenReachable("ford");

        handler.share(new ShareRequest(
                linkScope(), Map.of("recipients", List.of("ford"), "text", "have a look")));

        MaximegalonDocument item = captureItem();
        // The discriminator follows the payload — an OUTPUT_DOCUMENT without a
        // document would be a lie in the type.
        assertThat(item.getType()).isEqualTo(MaximegalonType.OUTPUT_TEXT);
        assertThat(item.getPayload()).doesNotContainKey("documentRef");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) item.getPayload().get("link");
        assertThat(link).containsEntry("url", "https://example.com/hit");
        assertThat(link).containsEntry("title", "Canyon test results");
        assertThat(item.getPayload()).containsEntry("snippet", "…the test is done…");
        // The reason stays the body; the snippet never merges into it, because
        // the body renders as Markdown and foreign text must stay a quote.
        assertThat(item.getBody()).isEqualTo("have a look");
        assertThat(item.getTitle()).isEqualTo("Mara Toon shared: Canyon test results");
    }

    @Test
    void share_documentAndSnippet_carriesBoth() {
        givenReachable("ford");
        ShareScope scope = new ShareScope(
                MARA, TENANT, PROJECT,
                new ShareSubject(null, null, "…the passage…",
                        DocumentRef.of(PROJECT, PATH)),
                DocumentDocument.builder()
                        .id("doc-1").tenantId(TENANT).projectId(PROJECT)
                        .path(PATH).title("Results").build());

        handler.share(new ShareRequest(
                scope, Map.of("recipients", List.of("ford"), "text", "this bit")));

        MaximegalonDocument item = captureItem();
        assertThat(item.getType()).isEqualTo(MaximegalonType.OUTPUT_DOCUMENT);
        assertThat(item.getDocumentRef()).isNotNull();
        assertThat(item.getPayload()).containsEntry("snippet", "…the passage…");
    }

    @Test
    void share_subjectTitle_winsOverDocumentTitle() {
        givenReachable("ford");
        ShareScope scope = new ShareScope(
                MARA, TENANT, PROJECT,
                new ShareSubject("What the sharer called it", null, null,
                        DocumentRef.of(PROJECT, PATH)),
                DocumentDocument.builder()
                        .id("doc-1").tenantId(TENANT).projectId(PROJECT)
                        .path(PATH).title("Results").build());

        handler.share(new ShareRequest(
                scope, Map.of("recipients", List.of("ford"), "text", "look")));

        assertThat(captureItem().getTitle())
                .isEqualTo("Mara Toon shared: What the sharer called it");
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenReachable(String... usernames) {
        List<UserDocument> all = new java.util.ArrayList<>();
        all.add(user("mara", "Mara Toon"));
        for (String name : usernames) {
            all.add(user(name, null));
        }
        when(userService.all(TENANT)).thenReturn(all);
    }

    private MaximegalonDocument captureItem() {
        ArgumentCaptor<MaximegalonDocument> captor = forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    void request_nullValueInTheSubmission_isDroppedNotThrown() {
        // `values` is raw JSON — the class doc says nothing here is trusted —
        // and Map.copyOf threw NullPointerException on a null value, so
        // {"text":null} produced a 500 for what is at most a 422. A key with
        // no value says the same as an absent key.
        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("text", null);
        raw.put("recipients", List.of("ford"));

        ShareRequest request = new ShareRequest(scope(), raw);

        assertThat(request.string("text")).isNull();
        assertThat(request.strings("recipients")).containsExactly("ford");
    }

    private ShareRequest request(Map<String, Object> values) {
        return new ShareRequest(scope(), values);
    }

    private static ShareScope scope() {
        return new ShareScope(
                MARA, TENANT, PROJECT,
                ShareSubject.ofDocument(DocumentRef.of(PROJECT, PATH)),
                DocumentDocument.builder()
                        .id("doc-1")
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .path(PATH)
                        .title("Results")
                        .build());
    }

    /** A subject with no document at all — a search hit, say. */
    private static ShareScope linkScope() {
        return new ShareScope(
                MARA, TENANT, PROJECT,
                new ShareSubject("Canyon test results", "https://example.com/hit",
                        "…the test is done…", null),
                null);
    }

    private static UserDocument user(String name, String title) {
        UserDocument doc = new UserDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setTitle(title);
        doc.setStatus(UserStatus.ACTIVE);
        return doc;
    }
}
