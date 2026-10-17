package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Service behaviour against an in-memory document store: scopes and their
 * folding, shadowing, the write rules (own authorisation, then SYSTEM), the
 * group visibility rule, moves and pictures.
 */
class ContactServiceTest {

    private static final String TENANT = "acme";
    private static final String USER = "anna";
    private static final String HUB = HomeBootstrapService.hubProjectName(USER);
    private static final String PROJECT = "website";
    private static final String TENANT_PROJECT = "_tenant";

    private final Map<String, DocumentDocument> docs = new LinkedHashMap<>();
    private final Map<String, byte[]> content = new LinkedHashMap<>();
    private final List<WriteActor> actors = new ArrayList<>();
    private final Set<String> writable = new HashSet<>();
    private final Set<String> readable = new HashSet<>();
    private final AtomicInteger ids = new AtomicInteger();

    private DocumentService documentService;
    private PermissionService permissionService;
    private HomeBootstrapService homeBootstrapService;
    private GravatarClient gravatarClient;
    private ContactService service;

    private final SecurityContext subject = SecurityContext.user(USER, TENANT, List.of());
    private final ContactCaller caller = ContactCaller.of(TENANT, subject, USER, PROJECT);

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        permissionService = mock(PermissionService.class);
        homeBootstrapService = mock(HomeBootstrapService.class);
        gravatarClient = mock(GravatarClient.class);
        readable.addAll(List.of(HUB, PROJECT, TENANT_PROJECT));
        writable.addAll(List.of(HUB, PROJECT));
        wireDocuments();
        wirePermissions();
        service = new ContactService(
                documentService,
                permissionService,
                homeBootstrapService,
                new ContactIndex(documentService),
                new ContactPhotoProcessor(64),
                gravatarClient);
    }

    // ── Scopes ────────────────────────────────────────────────────

    @Test
    void createDefaultsToTheUsersHubAndWritesAsSystem() {
        ContactEntry created = service.create(caller, card("Bob"), null);

        assertThat(created.scope()).isEqualTo(ContactScope.USER);
        assertThat(created.projectId()).isEqualTo(HUB);
        assertThat(docs).containsKey(HUB + "|" + ContactIds.cardPath(created.id()));
        assertThat(actors).last().extracting(WriteActor::reason).isEqualTo(WriteReason.SYSTEM);
        assertThat(actors).last().extracting(WriteActor::subject).isEqualTo(subject);
        verify(permissionService).enforce(subject, new Resource.Project(TENANT, HUB), Action.WRITE);
    }

    @Test
    void withoutUserTheDefaultIsTheProject() {
        ContactCaller headless = ContactCaller.of(TENANT, SecurityContext.SYSTEM, null, PROJECT);
        assertThat(service.defaultWriteScope(headless)).isEqualTo(ContactScope.PROJECT);
        assertThatThrownBy(() -> service.projectOf(headless, ContactScope.USER)).hasMessageContaining("without a user");
    }

    @Test
    void projectScopeInOwnHubFoldsIntoUser() {
        ContactCaller inHub = ContactCaller.of(TENANT, subject, USER, HUB);
        assertThat(service.scopeProjects(inHub)).containsOnlyKeys(ContactScope.USER, ContactScope.TENANT);
        ContactEntry created = service.create(inHub, card("Bob"), ContactScope.PROJECT);
        assertThat(created.scope()).isEqualTo(ContactScope.USER);
    }

    @Test
    void confinedCallerSeesOnlyItsOwnProject() {
        ContactCaller confined = caller.confined(PROJECT);
        assertThat(service.scopeProjects(confined)).containsOnlyKeys(ContactScope.PROJECT);
        assertThatThrownBy(() -> service.create(confined, card("Bob"), ContactScope.TENANT))
                .hasMessageContaining("out of reach");
    }

    @Test
    void tenantWriteNeedsWriteOnTenantProject() {
        assertThatThrownBy(() -> service.create(caller, card("Bob"), ContactScope.TENANT))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(docs).isEmpty();
    }

    // ── Reads ─────────────────────────────────────────────────────

    @Test
    void readsMergeAllScopesAndTheSpecificOneShadows() {
        writable.add(TENANT_PROJECT);
        ContactEntry tenantCard = service.create(caller, card("Shared Bob"), ContactScope.TENANT);
        // Same id, more specific scope: a local override.
        storeCard(PROJECT, tenantCard.id(), card("Project Bob"));
        service.create(caller, card("Private Carol"), ContactScope.USER);

        List<ContactEntry> all = service.search(caller, null, ContactSearch.Criteria.all());

        assertThat(all).extracting(e -> e.card().displayName()).containsExactly("Private Carol", "Project Bob");
        assertThat(service.get(caller, tenantCard.id(), null).scope()).isEqualTo(ContactScope.PROJECT);
        assertThat(service.get(caller, tenantCard.id(), ContactScope.TENANT)
                        .card()
                        .displayName())
                .isEqualTo("Shared Bob");
    }

    @Test
    void unreadableScopeIsLeftOutOfMergedReadsButRefusedWhenAsked() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);
        readable.remove(PROJECT);

        assertThat(service.search(caller, null, ContactSearch.Criteria.all())).isEmpty();
        assertThatThrownBy(() -> service.get(caller, bob.id(), ContactScope.PROJECT))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void brokenCardFilesAreReportedNotHidden() {
        String id = ContactIds.newId();
        put(HUB, ContactIds.cardPath(id), "this: [is not a card".getBytes(StandardCharsets.UTF_8), "application/yaml");

        assertThat(service.search(caller, null, ContactSearch.Criteria.all())).isEmpty();
        assertThat(service.broken(caller)).containsEntry(ContactScope.USER, List.of(ContactIds.cardPath(id)));
    }

    // ── Writes ────────────────────────────────────────────────────

    @Test
    void updateInTheWrongScopeNamesWhereTheCardLives() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);

        assertThatThrownBy(() -> service.update(caller, bob.id(), null, c -> c))
                .isInstanceOf(ContactNotFoundException.class)
                .hasMessageContaining("lives in project");
    }

    @Test
    void updateKeepsThePictureField() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry withPhoto = service.setPhoto(caller, bob.id(), null, ContactPhotoProcessorTestImages.square());
        ContactEntry renamed = service.update(
                caller,
                bob.id(),
                null,
                c -> c.toBuilder().fullName("Robert").photo(null).build());

        assertThat(withPhoto.photoPath()).isEqualTo(ContactIds.photoPath(bob.id(), "jpg"));
        assertThat(renamed.card().fullName()).isEqualTo("Robert");
        assertThat(renamed.card().photo()).isEqualTo(bob.id() + ".jpg");
        assertThat(renamed.photoPath()).isNotNull();
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> service.create(caller, ContactCard.builder().build(), null))
                .hasMessageContaining("needs a name");
    }

    @Test
    void deleteTrashesCardAndPictureAndReportsGroups() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        service.setPhoto(caller, bob.id(), null, ContactPhotoProcessorTestImages.square());
        ContactEntry group = service.create(caller, group("Friends", bob.id()), null);

        ContactService.GroupImpact impact = service.delete(caller, bob.id(), null);

        assertThat(docs.keySet()).noneMatch(k -> k.contains(bob.id()));
        assertThat(impact.groups()).extracting(ContactEntry::id).containsExactly(group.id());
    }

    // ── Groups ────────────────────────────────────────────────────

    @Test
    void groupMayHoldMembersOfItsOwnOrBroaderScope() {
        writable.add(TENANT_PROJECT);
        ContactEntry privateBob = service.create(caller, card("Bob"), ContactScope.USER);
        ContactEntry sharedCarol = service.create(caller, card("Carol"), ContactScope.TENANT);

        ContactEntry userGroup = service.create(caller, group("Mine", privateBob.id(), sharedCarol.id()), null);
        assertThat(userGroup.card().members()).containsExactly(privateBob.id(), sharedCarol.id());

        assertThatThrownBy(() -> service.create(caller, group("Shared", privateBob.id()), ContactScope.TENANT))
                .hasMessageContaining("not visible from a tenant group");
    }

    @Test
    void groupsDoNotNestAndOnlyGroupsHaveMembers() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry inner = service.create(caller, group("Inner", bob.id()), null);

        assertThatThrownBy(() -> service.create(caller, group("Outer", inner.id()), null))
                .hasMessageContaining("do not nest");
        assertThatThrownBy(() -> service.create(
                        caller,
                        card("Bob").toBuilder().members(List.of(bob.id())).build(),
                        null))
                .hasMessageContaining("Only a group");
    }

    @Test
    void changeMembersAddsAndRemoves() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        ContactEntry carol = service.create(caller, card("Carol"), null);
        ContactEntry team = service.create(caller, group("Team", bob.id()), null);

        ContactEntry changed = service.changeMembers(
                caller, team.id(), null, List.of(carol.id(), "mailto:x@y.org"), List.of(bob.id()));

        assertThat(changed.card().members()).containsExactly(carol.id(), "mailto:x@y.org");
        assertThat(service.resolveMembers(caller, changed))
                .containsEntry("mailto:x@y.org", null)
                .extractingByKey(carol.id())
                .extracting(e -> e.card().displayName())
                .isEqualTo("Carol");
    }

    // ── Move ──────────────────────────────────────────────────────

    @Test
    void moveKeepsIdAndPictureAndReportsStrandedGroups() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.PROJECT);
        service.setPhoto(caller, bob.id(), ContactScope.PROJECT, ContactPhotoProcessorTestImages.square());
        ContactEntry projectGroup = service.create(caller, group("Team", bob.id()), ContactScope.PROJECT);

        ContactService.MoveResult moved = service.move(caller, bob.id(), ContactScope.PROJECT, ContactScope.USER);

        assertThat(moved.entry().id()).isEqualTo(bob.id());
        assertThat(moved.entry().scope()).isEqualTo(ContactScope.USER);
        assertThat(moved.entry().photoPath()).isNotNull();
        assertThat(docs).containsKey(HUB + "|" + ContactIds.photoPath(bob.id(), "jpg"));
        assertThat(docs).doesNotContainKey(PROJECT + "|" + ContactIds.cardPath(bob.id()));
        assertThat(moved.strandedGroups()).extracting(ContactEntry::id).containsExactly(projectGroup.id());
    }

    // ── Pictures ──────────────────────────────────────────────────

    @Test
    void gravatarMissIsNoError() {
        ContactEntry bob = service.create(
                caller,
                card("Bob").toBuilder()
                        .emails(List.of(ContactValue.of("bob@x.org")))
                        .build(),
                null);
        when(gravatarClient.fetch(eq("bob@x.org"), any(Integer.class))).thenReturn(Optional.empty());

        assertThat(service.setPhotoFromGravatar(caller, bob.id(), null, null)).isEmpty();
        assertThat(service.get(caller, bob.id(), null).photoPath()).isNull();
    }

    @Test
    void gravatarNeedsAnAddress() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        assertThatThrownBy(() -> service.setPhotoFromGravatar(caller, bob.id(), null, null))
                .hasMessageContaining("no e-mail");
        verify(gravatarClient, never()).fetch(anyString(), any(Integer.class));
    }

    // ── Kind documents ────────────────────────────────────────────

    @Test
    void referenceDocumentResolvesThroughTheCascade() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        put(
                PROJECT,
                "people/bob.yaml",
                ContactCodec.serializeRef(bob.id(), null).getBytes(StandardCharsets.UTF_8),
                "application/yaml");
        put(
                PROJECT,
                "people/ghost.yaml",
                ContactCodec.serializeRef(ContactIds.newId(), null).getBytes(StandardCharsets.UTF_8),
                "application/yaml");

        ContactService.ResolvedDocument found = service.resolveDocument(caller, PROJECT, "people/bob.yaml");
        ContactService.ResolvedDocument ghost = service.resolveDocument(caller, PROJECT, "people/ghost.yaml");

        assertThat(found.card().displayName()).isEqualTo("Bob");
        assertThat(found.entry().scope()).isEqualTo(ContactScope.USER);
        assertThat(ghost.dangling()).isTrue();
        assertThat(ghost.card()).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────

    private static ContactCard card(String name) {
        return ContactCard.builder().fullName(name).build();
    }

    private static ContactCard group(String name, String... members) {
        return ContactCard.builder()
                .kind(ContactKind.GROUP)
                .fullName(name)
                .members(List.of(members))
                .build();
    }

    private void storeCard(String project, String id, ContactCard card) {
        put(
                project,
                ContactIds.cardPath(id),
                ContactCodec.serialize(card).getBytes(StandardCharsets.UTF_8),
                "application/yaml");
    }

    private DocumentDocument put(String project, String path, byte[] bytes, String mime) {
        DocumentDocument d = new DocumentDocument();
        d.setId("doc-" + ids.incrementAndGet());
        d.setTenantId(TENANT);
        d.setProjectId(project);
        d.setPath(path);
        d.setMimeType(mime);
        d.setVersion(1L);
        docs.put(project + "|" + path, d);
        content.put(d.getId(), bytes);
        return d;
    }

    private Optional<DocumentDocument> byId(String id) {
        return docs.values().stream().filter(d -> d.getId().equals(id)).findFirst();
    }

    private void wireDocuments() {
        when(documentService.listUnderFolder(eq(TENANT), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String project = inv.getArgument(1);
                    String prefix = inv.getArgument(2);
                    return docs.values().stream()
                            .filter(d -> d.getProjectId().equals(project)
                                    && d.getPath().startsWith(prefix))
                            .toList();
                });
        when(documentService.findByPath(eq(TENANT), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(docs.get(inv.getArgument(1) + "|" + inv.getArgument(2))));
        when(documentService.readContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new String(
                        content.get(((DocumentDocument) inv.getArgument(0)).getId()), StandardCharsets.UTF_8));
        when(documentService.loadContent(any(DocumentDocument.class)))
                .thenAnswer(
                        inv -> new ByteArrayInputStream(content.get(((DocumentDocument) inv.getArgument(0)).getId())));
        when(documentService.createText(
                        eq(TENANT),
                        anyString(),
                        anyString(),
                        any(),
                        isNull(),
                        anyString(),
                        any(),
                        any(WriteActor.class)))
                .thenAnswer(inv -> {
                    actors.add(inv.getArgument(7));
                    String key = inv.getArgument(1) + "|" + inv.getArgument(2);
                    if (docs.containsKey(key)) throw new DocumentService.DocumentAlreadyExistsException("exists");
                    return put(
                            inv.getArgument(1),
                            inv.getArgument(2),
                            ((String) inv.getArgument(5)).getBytes(StandardCharsets.UTF_8),
                            "application/yaml");
                });
        when(documentService.update(anyString(), any(), isNull(), anyString(), isNull(), any(WriteActor.class)))
                .thenAnswer(inv -> {
                    actors.add(inv.getArgument(5));
                    DocumentDocument d = byId(inv.getArgument(0)).orElseThrow();
                    content.put(d.getId(), ((String) inv.getArgument(3)).getBytes(StandardCharsets.UTF_8));
                    d.setVersion(d.getVersion() + 1);
                    return d;
                });
        when(documentService.trash(anyString(), any(WriteActor.class))).thenAnswer(inv -> {
            actors.add(inv.getArgument(1));
            DocumentDocument d = byId(inv.getArgument(0)).orElseThrow();
            docs.remove(d.getProjectId() + "|" + d.getPath());
            return d;
        });
        when(documentService.createOrReplaceBinary(
                        eq(TENANT),
                        anyString(),
                        anyString(),
                        any(byte[].class),
                        anyString(),
                        any(),
                        isNull(),
                        isNull(),
                        any(),
                        any(WriteActor.class)))
                .thenAnswer(inv -> {
                    actors.add(inv.getArgument(9));
                    String key = inv.getArgument(1) + "|" + inv.getArgument(2);
                    DocumentDocument existing = docs.get(key);
                    if (existing != null) {
                        content.put(existing.getId(), inv.getArgument(3));
                        existing.setVersion(existing.getVersion() + 1);
                        return existing;
                    }
                    return put(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4));
                });
    }

    private void wirePermissions() {
        when(permissionService.check(any(SecurityContext.class), any(Resource.class), any(Action.class)))
                .thenAnswer(inv -> allowed(inv.getArgument(1), inv.getArgument(2)));
        doAnswer(inv -> {
                    if (!allowed(inv.getArgument(1), inv.getArgument(2))) {
                        throw new PermissionDeniedException(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    }
                    return null;
                })
                .when(permissionService)
                .enforce(any(SecurityContext.class), any(Resource.class), any(Action.class));
    }

    private boolean allowed(Resource resource, Action action) {
        String project =
                switch (resource) {
                    case Resource.Project p -> p.projectName();
                    case Resource.Document d -> d.projectName();
                    default -> "";
                };
        return action == Action.READ ? readable.contains(project) : writable.contains(project);
    }
}
