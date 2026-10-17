package de.mhus.vance.addon.brain.contacts.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactConflictException;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactNotFoundException;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.ContactVCardService;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code contact_*} tools over a mocked {@link ContactService}: how a
 * tool call becomes a {@link ContactCaller}, how params reach the service,
 * and how the service's refusals turn into {@link ToolException}s.
 */
class ContactToolsTest {

    private static final String TENANT = "acme";
    private static final String USER = "anna";
    private static final String PROJECT = "website";
    private static final String ID = "0b6f5f1e-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

    private final SecurityContext subject = SecurityContext.user(USER, TENANT, List.of());
    private final ToolInvocationContext ctx = new ToolInvocationContext(TENANT, PROJECT, "s1", "p1", USER);

    private EddieContext eddieContext;
    private ContactService contactService;
    private ContactToolSupport support;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        contactService = mock(ContactService.class);
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(subject);
        when(eddieContext.resolveProject(any(), eq(ctx), eq(false))).thenReturn(project(PROJECT));
        when(contactService.resolveMembers(any(), any())).thenReturn(Map.of());
        when(contactService.groupsContaining(any(), any())).thenReturn(List.of());
        support = new ContactToolSupport(eddieContext, contextFactory, contactService);
    }

    private static ProjectDocument project(String name) {
        return ProjectDocument.builder().name(name).build();
    }

    private static ContactEntry entry(ContactScope scope, ContactCard card) {
        return new ContactEntry(ID, scope, PROJECT, "doc-1", card, null, null);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── ContactToolSupport.caller ─────────────────────────────────

    @Test
    void callerCarriesTheResolvedProjectAndIsNotConfined() {
        ContactCaller caller = support.caller(params(), ctx);

        assertThat(caller.tenantId()).isEqualTo(TENANT);
        assertThat(caller.subject()).isSameAs(subject);
        assertThat(caller.userLogin()).isEqualTo(USER);
        assertThat(caller.projectId()).isEqualTo(PROJECT);
        assertThat(caller.confinedTo()).isNull();
    }

    @Test
    void callerWithoutAnyProjectKeepsUserAndTenantScopes() {
        when(eddieContext.resolveProject(any(), eq(ctx), eq(false))).thenThrow(new ToolException("no project"));

        ContactCaller caller = support.caller(params(), ctx);

        assertThat(caller.projectId()).isNull();
        assertThat(caller.confinedTo()).isNull();
    }

    @Test
    void anExplicitProjectThatDoesNotResolveIsAnError() {
        when(eddieContext.resolveProject(anyMap(), eq(ctx), eq(false)))
                .thenThrow(new ToolException("Project 'ghost' not found"));

        assertThatThrownBy(() -> support.caller(params("projectId", "ghost"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void confinedProcessIsLockedToItsOwnProject() {
        when(eddieContext.isConfined(ctx)).thenReturn(true);

        ContactCaller caller = support.caller(params(), ctx);

        assertThat(caller.confinedTo()).isEqualTo(PROJECT);
    }

    @Test
    void confinedProcessWithoutAProjectIsRefused() {
        ToolInvocationContext headless = new ToolInvocationContext(TENANT, null, "s1", "p1", USER);
        when(eddieContext.resolveProject(any(), eq(headless), eq(false))).thenThrow(new ToolException("no project"));
        when(eddieContext.isConfined(headless)).thenReturn(true);

        assertThatThrownBy(() -> support.caller(params(), headless))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Confined process without a project");
    }

    // ── ContactToolSupport.run ────────────────────────────────────

    @Test
    void serviceRefusalsBecomeToolErrorsWithTheServiceSentence() {
        assertThatThrownBy(() -> ContactToolSupport.run(() -> {
                    throw new ContactNotFoundException("No contact 'x'");
                }))
                .isInstanceOf(ToolException.class)
                .hasMessage("No contact 'x'");
        assertThatThrownBy(() -> ContactToolSupport.run(() -> {
                    throw new IllegalArgumentException("A name is required");
                }))
                .isInstanceOf(ToolException.class)
                .hasMessage("A name is required");
        assertThatThrownBy(() -> ContactToolSupport.run(() -> {
                    throw new KindCodecException("bad yaml");
                }))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unreadable contact data")
                .hasMessageContaining("bad yaml");
        assertThatThrownBy(() -> ContactToolSupport.run(() -> {
                    throw new ContactConflictException("Changed meanwhile.", null);
                }))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Changed meanwhile.")
                .hasMessageContaining("retry");
    }

    // ── contact_create ────────────────────────────────────────────

    @Test
    void createPassesCardAndScope() {
        when(contactService.create(any(), any(), any()))
                .thenAnswer(inv -> entry(inv.getArgument(2), inv.getArgument(1)));

        Map<String, Object> result = new ContactCreateTool(support)
                .invoke(params("fullName", "Anna Berger", "emails", List.of("anna@x.org"), "scope", "project"), ctx);

        ArgumentCaptor<ContactCard> card = ArgumentCaptor.forClass(ContactCard.class);
        verify(contactService).create(any(ContactCaller.class), card.capture(), eq(ContactScope.PROJECT));
        assertThat(card.getValue().fullName()).isEqualTo("Anna Berger");
        assertThat(card.getValue().emails()).extracting(ContactValue::value).containsExactly("anna@x.org");
        assertThat(result)
                .containsEntry("id", ID)
                .containsEntry("scope", "project")
                .containsEntry("name", "Anna Berger");
    }

    @Test
    void createWithoutScopeLeavesTheDefaultToTheService() {
        when(contactService.create(any(), any(), isNull()))
                .thenAnswer(inv -> entry(ContactScope.USER, inv.getArgument(1)));

        Map<String, Object> result = new ContactCreateTool(support).invoke(params("fullName", "Bob"), ctx);

        assertThat(result).containsEntry("scope", "user");
    }

    @Test
    void createRefusalBecomesToolError() {
        when(contactService.create(any(), any(), any())).thenThrow(new IllegalArgumentException("A name is required"));

        assertThatThrownBy(() -> new ContactCreateTool(support).invoke(params("note", "x"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessage("A name is required");
    }

    @Test
    void createWithAnUnknownScopeIsAToolError() {
        assertThatThrownBy(() -> new ContactCreateTool(support).invoke(params("fullName", "A", "scope", "galaxy"), ctx))
                .isInstanceOf(ToolException.class);
        verify(contactService, never()).create(any(), any(), any());
    }

    // ── contact_update ────────────────────────────────────────────

    @Test
    void updateWithoutAnyFieldIsRefusedBeforeTheService() {
        assertThatThrownBy(() -> new ContactUpdateTool(support).invoke(params("id", ID), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Nothing to change");
        verify(contactService, never()).update(any(), any(), any(), any());
    }

    @Test
    void updateRequiresTheId() {
        assertThatThrownBy(() -> new ContactUpdateTool(support).invoke(params("title", "CEO"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateAppliesThePatchToTheStoredCard() {
        ContactCard stored =
                ContactCard.builder().fullName("Anna").title("Intern").build();
        when(contactService.update(any(), eq(ID), eq(ContactScope.PROJECT), any()))
                .thenAnswer(inv ->
                        entry(ContactScope.PROJECT, ((UnaryOperator<ContactCard>) inv.getArgument(3)).apply(stored)));

        Map<String, Object> result =
                new ContactUpdateTool(support).invoke(params("id", ID, "scope", "project", "title", "CEO"), ctx);

        ArgumentCaptor<UnaryOperator<ContactCard>> change = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(contactService).update(any(), eq(ID), eq(ContactScope.PROJECT), change.capture());
        ContactCard next = change.getValue().apply(stored);
        assertThat(next.title()).isEqualTo("CEO");
        assertThat(next.fullName()).isEqualTo("Anna");
        assertThat(result)
                .containsEntry("updated", true)
                .containsEntry("id", ID)
                .containsEntry("title", "CEO");
    }

    @Test
    void updateOfAMissingContactBecomesToolError() {
        when(contactService.update(any(), any(), any(), any())).thenThrow(new ContactNotFoundException("No contact"));

        assertThatThrownBy(() -> new ContactUpdateTool(support).invoke(params("id", ID, "title", "CEO"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessage("No contact");
    }

    // ── contact_move ──────────────────────────────────────────────

    @Test
    void moveRequiresTarget() {
        assertThatThrownBy(() -> new ContactMoveTool(support).invoke(params("id", ID), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("to is required");
        verify(contactService, never()).move(any(), any(), any(), any());
    }

    @Test
    void moveReportsTheGroupsThatLostTheContact() {
        ContactEntry group = new ContactEntry(
                "1b6f5f1e-3c4d-4e5f-8a9b-0c1d2e3f4a5b",
                ContactScope.TENANT,
                "_tenant",
                "doc-2",
                ContactCard.builder()
                        .kind(ContactKind.GROUP)
                        .fullName("Everyone")
                        .build(),
                null,
                null);
        when(contactService.move(any(), eq(ID), eq(ContactScope.TENANT), eq(ContactScope.USER)))
                .thenReturn(new ContactService.MoveResult(
                        entry(
                                ContactScope.USER,
                                ContactCard.builder().fullName("A").build()),
                        List.of(group)));

        Map<String, Object> result =
                new ContactMoveTool(support).invoke(params("id", ID, "from", "tenant", "to", "user"), ctx);

        assertThat(result)
                .containsEntry("id", ID)
                .containsEntry("scope", "user")
                .containsKey("hint");
        assertThat((List<?>) result.get("groupsThatLostIt")).hasSize(1);
    }

    @Test
    void moveWithoutStrandedGroupsHasNoHint() {
        when(contactService.move(any(), eq(ID), isNull(), eq(ContactScope.PROJECT)))
                .thenReturn(new ContactService.MoveResult(
                        entry(
                                ContactScope.PROJECT,
                                ContactCard.builder().fullName("A").build()),
                        List.of()));

        Map<String, Object> result = new ContactMoveTool(support).invoke(params("id", ID, "to", "project"), ctx);

        assertThat(result).doesNotContainKeys("hint", "groupsThatLostIt");
    }

    // ── contact_photo_set ─────────────────────────────────────────

    @Test
    void photoSetNeedsExactlyOneOfPathOrRemove() {
        ContactPhotoSetTool tool = new ContactPhotoSetTool(support);

        assertThatThrownBy(() -> tool.invoke(params("id", ID), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Pass either photoPath or remove: true");
        assertThatThrownBy(() -> tool.invoke(params("id", ID, "photoPath", "a.png", "remove", true), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Pass either photoPath or remove: true");
        verifyNoInteractions(contactService);
    }

    @Test
    void photoSetFromDocument() {
        when(contactService.setPhotoFromDocument(any(), eq(ID), isNull(), eq("images/anna.png")))
                .thenReturn(new ContactEntry(
                        ID,
                        ContactScope.USER,
                        PROJECT,
                        "doc-1",
                        ContactCard.builder().fullName("A").build(),
                        "_vance/contacts/x.jpg",
                        1L));

        Map<String, Object> result =
                new ContactPhotoSetTool(support).invoke(params("id", ID, "photoPath", "images/anna.png"), ctx);

        assertThat(result).containsEntry("hasPhoto", true);
    }

    @Test
    void photoRemoveAcceptsTheStringTrue() {
        when(contactService.removePhoto(any(), eq(ID), isNull()))
                .thenReturn(entry(
                        ContactScope.USER, ContactCard.builder().fullName("A").build()));

        Map<String, Object> result = new ContactPhotoSetTool(support).invoke(params("id", ID, "remove", "true"), ctx);

        assertThat(result).containsEntry("hasPhoto", false);
        verify(contactService, never()).setPhotoFromDocument(any(), any(), any(), any());
    }

    // ── contact_import_vcf ────────────────────────────────────────

    @Test
    void importRefusesAnOversizedDocumentWithoutReadingIt() {
        DocumentService documentService = mock(DocumentService.class);
        ContactVCardService vcardService = mock(ContactVCardService.class);
        DocumentDocument doc = new DocumentDocument();
        doc.setSize(ContactVCardService.MAX_IMPORT_BYTES + 1L);
        when(documentService.findByPath(TENANT, PROJECT, "big.vcf")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> new ContactImportVcfTool(support, vcardService, documentService)
                        .invoke(params("path", "big.vcf"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("too large");
        verify(documentService, never()).readContent(any(DocumentDocument.class));
        verifyNoInteractions(vcardService);
    }
}
