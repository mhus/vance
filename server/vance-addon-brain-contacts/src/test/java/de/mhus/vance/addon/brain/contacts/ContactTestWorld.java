package de.mhus.vance.addon.brain.contacts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
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

/**
 * In-memory world for service tests: a mocked {@link DocumentService}
 * backed by maps, a permission service driven by {@link #readable} and
 * {@link #writable}, and a real {@link ContactService} on top.
 */
abstract class ContactTestWorld {

    protected static final String TENANT = "acme";
    protected static final String USER = "anna";
    protected static final String HUB = HomeBootstrapService.hubProjectName(USER);
    protected static final String PROJECT = "website";
    protected static final String TENANT_PROJECT = "_tenant";

    protected final Map<String, DocumentDocument> docs = new LinkedHashMap<>();
    protected final Map<String, byte[]> content = new LinkedHashMap<>();
    protected final List<WriteActor> actors = new ArrayList<>();
    protected final Set<String> writable = new HashSet<>();
    protected final Set<String> readable = new HashSet<>();
    protected final AtomicInteger ids = new AtomicInteger();

    protected DocumentService documentService;
    protected PermissionService permissionService;
    protected HomeBootstrapService homeBootstrapService;
    protected GravatarClient gravatarClient;
    protected ContactService service;

    protected final SecurityContext subject = SecurityContext.user(USER, TENANT, List.of());
    protected final ContactCaller caller = ContactCaller.of(TENANT, subject, USER, PROJECT);

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

    // ── Fixtures ──────────────────────────────────────────────────

    protected static ContactCard card(String name) {
        return ContactCard.builder().fullName(name).build();
    }

    protected static ContactCard group(String name, String... members) {
        return ContactCard.builder()
                .kind(ContactKind.GROUP)
                .fullName(name)
                .members(List.of(members))
                .build();
    }

    protected void storeCard(String project, String id, ContactCard card) {
        put(
                project,
                ContactIds.cardPath(id),
                ContactCodec.serialize(card).getBytes(StandardCharsets.UTF_8),
                "application/yaml");
    }

    protected DocumentDocument put(String project, String path, byte[] bytes, String mime) {
        DocumentDocument d = new DocumentDocument();
        d.setId("doc-" + ids.incrementAndGet());
        d.setTenantId(TENANT);
        d.setProjectId(project);
        d.setPath(path);
        d.setMimeType(mime);
        // The real header strategy mirrors $meta.kind onto the row; do the same.
        java.util.regex.Matcher kind = java.util.regex.Pattern.compile("(?m)^  kind: ([a-z-]+)$")
                .matcher(new String(bytes, StandardCharsets.UTF_8));
        if (mime.contains("yaml") && kind.find()) d.setKind(kind.group(1));
        d.setVersion(1L);
        docs.put(project + "|" + path, d);
        content.put(d.getId(), bytes);
        return d;
    }

    protected Optional<DocumentDocument> byId(String id) {
        return docs.values().stream().filter(d -> d.getId().equals(id)).findFirst();
    }

    protected void wireDocuments() {
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

    protected void wirePermissions() {
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

    protected boolean allowed(Resource resource, Action action) {
        String project =
                switch (resource) {
                    case Resource.Project p -> p.projectName();
                    case Resource.Document d -> d.projectName();
                    default -> "";
                };
        return action == Action.READ ? readable.contains(project) : writable.contains(project);
    }
}
