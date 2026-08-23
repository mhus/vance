package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code $meta.privileged} authority gate (F1). A privileged document
 * carries {@code runAs} execution authority, so DocumentService demands
 * Document {@code ADMIN} — beyond the ordinary write check — in two cases:
 * SETTING the flag at create-time, and MODIFYING an already-privileged
 * document. Modelled with a {@link PermissionService} that denies only
 * {@code ADMIN} (a WRITER-level subject), so the guard's extra check is what
 * the assertions observe.
 */
class DocumentServicePrivilegedTest {

    private DocumentRepository repository;
    private DocumentHeaderParser headerParser;
    private DocumentService service;

    private final WriteActor writer =
            WriteActor.user(SecurityContext.user("alice", "acme", List.of()));

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);

        // Models the real chain: PermissionService short-circuits a SYSTEM
        // subject before any resolver runs, and the resolver behind it denies
        // ADMIN to this WRITER-level user. Modelled rather than blanket-denied
        // so the two SYSTEM shapes stay distinguishable — WriteActor.SYSTEM
        // (SYSTEM subject) must pass, WriteActor.system(user) must not.
        PermissionService permissionService = mock(PermissionService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            SecurityContext subject = inv.getArgument(0);
            if (subject.subjectType() == de.mhus.vance.shared.permission.SubjectType.SYSTEM) {
                return null;
            }
            throw new PermissionDeniedException(
                    subject, new Resource.Document("acme", "proj", "x"), Action.ADMIN);
        }).when(permissionService).enforce(any(), any(), eq(Action.ADMIN), any());
        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> psp = mock(ObjectProvider.class);
        when(psp.getObject()).thenReturn(permissionService);

        when(storageService.store(any(), any(), any())).thenAnswer(inv -> {
            java.io.InputStream stream = inv.getArgument(2);
            long size = stream.readAllBytes().length;
            return new StorageService.StorageInfo(
                    "blob-" + java.util.UUID.randomUUID(), size, new Date(), null, null);
        });

        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, psp);
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
    }

    private DocumentDocument doc(String id, boolean privileged) {
        DocumentDocument d = DocumentDocument.builder()
                .tenantId("acme").projectId("proj").path("_vance/scheduler/x.yaml")
                .mimeType("application/yaml").build();
        d.setId(id);
        d.setPrivileged(privileged);
        return d;
    }

    @Test
    void modifyingPrivilegedDocument_requiresAdmin() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc("d1", true)));

        assertThatThrownBy(() -> service.update(
                "d1", null, null, "new body", null, writer))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void modifyingNonPrivilegedDocument_needsNoAdmin() {
        when(repository.findById("d2")).thenReturn(Optional.of(doc("d2", false)));
        when(repository.save(any(DocumentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        try {
            when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        // The privileged guard must not fire — no ADMIN demand for a plain doc.
        assertThatCode(() -> service.update("d2", null, null, "body", null, writer))
                .doesNotThrowAnyException();
    }

    @Test
    void deletingPrivilegedDocument_requiresAdmin() {
        when(repository.findById("d3")).thenReturn(Optional.of(doc("d3", true)));

        assertThatThrownBy(() -> service.delete("d3", writer))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void changingLockOnPrivilegedDocument_requiresAdmin() {
        when(repository.findById("d4")).thenReturn(Optional.of(doc("d4", true)));

        assertThatThrownBy(() -> service.setLockedFor("d4", List.of(), writer))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void systemReasonDoesNotWaiveThePrivilegedGate() {
        // WriteActor.system(subject) is the "server code vouches for this user"
        // channel, and PermissionService short-circuits it — which is how the
        // scheduler / hook / event tools get past the reserved-prefix rule for
        // _vance/. Honouring it here too would mean a plain WRITER could plant
        // a runAs-carrying document through exactly those tools, i.e. the gate
        // would be off on the only paths it exists for.
        when(repository.findById("d5")).thenReturn(Optional.of(doc("d5", true)));

        assertThatThrownBy(() -> service.update(
                "d5", null, null, "new body", null,
                WriteActor.system(writer.subject())))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void aGenuinelyUserlessSystemWriteStillPasses() {
        // WriteActor.SYSTEM carries a SYSTEM *subject* — migrations, bootstrap,
        // lifecycle. There is no principal to hold responsible, and refusing it
        // would break the paths that legitimately install privileged documents.
        when(repository.findById("d6")).thenReturn(Optional.of(doc("d6", true)));
        when(repository.save(any(DocumentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        try {
            when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        assertThatCode(() -> service.update("d6", null, null, "body", null, WriteActor.SYSTEM))
                .doesNotThrowAnyException();
    }
}
