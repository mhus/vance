package de.mhus.vance.addon.brain.contacts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.ContactVCardService;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link ContactsController} over a mocked service: a body the service
 * cannot take is refused with {@link IllegalArgumentException} (400 via
 * {@link ContactsErrorAdvice}) instead of reaching it as {@code null}.
 */
class ContactsControllerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "website";
    private static final String ID = "0b6f5f1e-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

    private final SecurityContext subject = SecurityContext.user("anna", TENANT, List.of());
    private ContactService contactService;
    private ContactVCardService vcardService;
    private HttpServletRequest request;
    private ContactsController controller;

    @BeforeEach
    void setUp() {
        contactService = mock(ContactService.class);
        vcardService = mock(ContactVCardService.class);
        RequestAuthority authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        when(authority.contextOf(request)).thenReturn(subject);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("anna");
        when(contactService.groupsContaining(any(), any())).thenReturn(List.of());
        controller = new ContactsController(contactService, vcardService, authority);
    }

    private static ContactEntry entry(ContactScope scope) {
        return new ContactEntry(
                ID,
                scope,
                PROJECT,
                "doc-1",
                ContactCard.builder().fullName("Anna").build(),
                null,
                null);
    }

    @Test
    void createWithoutCardIsABadRequest() {
        assertThatThrownBy(() -> controller.create(TENANT, PROJECT, new ContactWriteRequest(null, null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'card' is required");
        verify(contactService, never()).create(any(), any(), any());
    }

    @Test
    void createHandsTheCallerCardAndScopeToTheService() {
        ContactCard card = ContactCard.builder().fullName("Anna").build();
        when(contactService.create(any(), eq(card), eq(ContactScope.PROJECT))).thenReturn(entry(ContactScope.PROJECT));

        ContactView view =
                controller.create(TENANT, PROJECT, new ContactWriteRequest(card, ContactScope.PROJECT), request);

        ArgumentCaptor<ContactCaller> caller = ArgumentCaptor.forClass(ContactCaller.class);
        verify(contactService).create(caller.capture(), eq(card), eq(ContactScope.PROJECT));
        assertThat(caller.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(caller.getValue().userLogin()).isEqualTo("anna");
        assertThat(caller.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.scope()).isEqualTo(ContactScope.PROJECT);
        assertThat(view.hasPhoto()).isFalse();
    }

    @Test
    void replaceWithoutCardIsABadRequest() {
        assertThatThrownBy(() -> controller.replace(
                        TENANT, ID, PROJECT, new ContactWriteRequest(null, ContactScope.USER), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'card' is required");
        verify(contactService, never()).update(any(), any(), any(), any());
    }

    @Test
    void moveWithoutTargetIsABadRequest() {
        assertThatThrownBy(() ->
                        controller.move(TENANT, ID, PROJECT, new MoveContactRequest(ContactScope.USER, null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'to' is required");
        verify(contactService, never()).move(any(), any(), any(), any());
    }

    @Test
    void moveDelegates() {
        when(contactService.move(any(), eq(ID), isNull(), eq(ContactScope.TENANT)))
                .thenReturn(new ContactService.MoveResult(entry(ContactScope.TENANT), List.of()));

        MoveContactView view =
                controller.move(TENANT, ID, PROJECT, new MoveContactRequest(null, ContactScope.TENANT), request);

        assertThat(view.contact().scope()).isEqualTo(ContactScope.TENANT);
        assertThat(view.strandedGroups()).isEmpty();
    }

    @Test
    void oversizedImportIsRefusedBeforeItIsRead() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(ContactVCardService.MAX_IMPORT_BYTES + 1L);

        assertThatThrownBy(() -> controller.importVcf(TENANT, PROJECT, null, file, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
        verify(file, never()).getBytes();
        verifyNoInteractions(vcardService);
    }

    @Test
    void importPassesTheTextAndShapesTheReport() throws Exception {
        String vcf = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Anna\r\nEND:VCARD\r\n";
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) vcf.length());
        when(file.getBytes()).thenReturn(vcf.getBytes(StandardCharsets.UTF_8));
        when(vcardService.importVcf(any(), eq(vcf), eq(ContactScope.USER)))
                .thenReturn(new ContactVCardService.ImportReport(
                        ContactScope.USER,
                        1,
                        0,
                        List.of(new ContactVCardService.Skipped(2, "Bob", "no name")),
                        List.of("w1")));

        ContactImportView view = controller.importVcf(TENANT, PROJECT, "user", file, request);

        assertThat(view.created()).isEqualTo(1);
        assertThat(view.skipped()).containsExactly("Bob: no name");
        assertThat(view.warnings()).containsExactly("w1");
    }
}
