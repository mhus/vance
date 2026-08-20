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
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
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
    private InboxItemService inboxItemService;
    private PermissionService permissionService;
    private InboxShareHandler handler;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        inboxItemService = mock(InboxItemService.class);
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

        InboxItemDocument item = captureItem();
        assertThat(item.getType()).isEqualTo(InboxItemType.OUTPUT_DOCUMENT);
        assertThat(item.isRequiresAction()).isFalse();
        assertThat(item.getAssignedToUserId()).isEqualTo("ford");
        assertThat(item.getOriginatorUserId()).isEqualTo("mara");
        assertThat(item.getTitle()).isEqualTo("Mara Toon shared: results.md");
        assertThat(item.getBody()).isEqualTo("test is done");
        assertThat(item.getTags()).containsExactly("share");
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) item.getPayload().get("documentRef");
        assertThat(ref).containsEntry("projectId", PROJECT);
        assertThat(ref).containsEntry("path", PATH);
        assertThat(ref).containsEntry("documentId", "doc-1");
        assertThat(item.getPayload()).doesNotContainKey("content");
    }

    @Test
    void share_multipleRecipients_getOneItemEach() {
        givenReachable("ford", "zaphod");

        ShareResult result = handler.share(request(
                Map.of("recipients", List.of("ford", "zaphod"), "text", "look")));

        verify(inboxItemService, times(2)).create(any(InboxItemDocument.class));
        assertThat(result.details()).containsEntry("recipients", List.of("ford", "zaphod"));
        assertThat(result.message()).contains("2");
    }

    @Test
    void share_missingReason_isRefused() {
        givenReachable("ford");

        assertThatThrownBy(() -> handler.share(request(Map.of("recipients", List.of("ford")))))
                .isInstanceOf(ShareException.class);

        verify(inboxItemService, never()).create(any(InboxItemDocument.class));
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

        verify(inboxItemService, times(1)).create(any(InboxItemDocument.class));
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

        verify(inboxItemService, never()).create(any(InboxItemDocument.class));
    }

    @Test
    void share_sharerWithoutTitle_titleFallsBackToUsername() {
        givenReachable("ford");
        when(userService.findByTenantAndName(TENANT, "mara"))
                .thenReturn(Optional.of(user("mara", null)));

        handler.share(request(Map.of("recipients", List.of("ford"), "text", "look")));

        assertThat(captureItem().getTitle()).isEqualTo("mara shared: results.md");
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

    private InboxItemDocument captureItem() {
        ArgumentCaptor<InboxItemDocument> captor = forClass(InboxItemDocument.class);
        verify(inboxItemService).create(captor.capture());
        return captor.getValue();
    }

    private ShareRequest request(Map<String, Object> values) {
        return new ShareRequest(scope(), values);
    }

    private static ShareScope scope() {
        return new ShareScope(MARA, TENANT, PROJECT, PATH, DocumentDocument.builder()
                .id("doc-1")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(PATH)
                .title("Results")
                .build());
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
