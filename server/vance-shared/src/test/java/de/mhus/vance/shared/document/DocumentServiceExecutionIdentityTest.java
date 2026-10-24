package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.RunAsReader;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Nobody writes a document the server executes under an identity stronger than
 * their own: the creator of a scheduler/hook/event, or the {@code runAs} of a
 * privileged one. {@link PermissionService#enforceMayActAs} is mocked to refuse
 * {@code root} for {@code alice}; what is observed is whether the document layer
 * asks.
 */
class DocumentServiceExecutionIdentityTest {

    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of());
    private static final String SCHEDULER = "_vance/scheduler/nightly.yaml";

    private DocumentRepository repository;
    private DocumentHeaderParser headerParser;
    private PermissionService permissionService;
    private DocumentArchiveService archiveService;
    private DocumentService service;

    /** Executes paths under {@code _vance/scheduler/}; reads a {@code runAs:} line. */
    private static final RunAsReader SCHEDULER_READER = new RunAsReader() {
        @Override
        public boolean executes(String path) {
            return path.startsWith("_vance/scheduler/");
        }

        @Override
        public @Nullable String declaredRunAs(String path, String content) {
            return content.lines()
                    .filter(l -> l.startsWith("runAs:"))
                    .map(l -> l.substring("runAs:".length()).trim())
                    .findFirst()
                    .orElse(null);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        headerParser = mock(DocumentHeaderParser.class);
        permissionService = mock(PermissionService.class);
        archiveService = mock(DocumentArchiveService.class);
        doThrow(new PermissionDeniedException(ALICE, new Resource.User("acme", "root"), Action.IMPERSONATE))
                .when(permissionService)
                .enforceMayActAs(ALICE, "root");
        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> psp = mock(ObjectProvider.class);
        when(psp.getObject()).thenReturn(permissionService);
        when(storageService.store(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            java.io.InputStream stream = inv.getArgument(2);
            long size = stream.readAllBytes().length;
            return new StorageService.StorageInfo("blob-" + java.util.UUID.randomUUID(), size, new Date(), null, null);
        });
        when(repository.save(any(DocumentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());

        service = new DocumentService(
                repository,
                storageService,
                mock(MongoTemplate.class),
                mock(ResourcePatternResolver.class),
                headerParser,
                archiveService,
                mock(SettingService.class),
                psp);
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
        @SuppressWarnings("unchecked")
        ObjectProvider<RunAsReader> readers = mock(ObjectProvider.class);
        when(readers.stream()).thenAnswer(inv -> Stream.of(SCHEDULER_READER));
        ReflectionTestUtils.setField(service, "runAsReaders", readers);
    }

    private DocumentDocument doc(String path, String createdBy, boolean privileged) {
        DocumentDocument d = DocumentDocument.builder()
                .tenantId("acme")
                .projectId("proj")
                .path(path)
                .mimeType("application/yaml")
                .createdBy(createdBy)
                .build();
        d.setId("d1");
        d.setPrivileged(privileged);
        return d;
    }

    @Test
    void editingASchedulerCreatedByAStrongerUser_isDenied() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "root", false)));

        assertThatThrownBy(() -> service.update("d1", null, null, "cron: '0 0 * * *'", null, WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void theToolChannel_doesNotLiftTheCheck() {
        // The authoring tools write as WriteActor.system(subject); the identity
        // check runs under the real subject regardless.
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "root", false)));

        assertThatThrownBy(() -> service.update("d1", null, null, "x", null, WriteActor.system(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void editingOwnScheduler_passes() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "alice", false)));

        assertThatCode(() -> service.update("d1", null, null, "cron: '0 0 * * *'", null, WriteActor.user(ALICE)))
                .doesNotThrowAnyException();
    }

    @Test
    void pointingAPrivilegedSchedulerAtAStrongerUser_isDenied() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "alice", true)));

        assertThatThrownBy(() -> service.update("d1", null, null, "runAs: root", null, WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void runAsInANonPrivilegedDocument_isNotHonouredAndNotChecked() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "alice", false)));

        assertThatCode(() -> service.update("d1", null, null, "runAs: root", null, WriteActor.user(ALICE)))
                .doesNotThrowAnyException();
        verify(permissionService, never()).enforceMayActAs(ALICE, "root");
    }

    @Test
    void ordinaryDocuments_areNotChecked() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc("notes/plan.md", "root", false)));

        assertThatCode(() -> service.update("d1", null, null, "text", null, WriteActor.user(ALICE)))
                .doesNotThrowAnyException();
        verify(permissionService, never()).enforceMayActAs(any(), eq("root"));
    }

    // ── move / restore: the target path is checked ──────────────────────────

    @Test
    void movingAStrongerUsersDocumentIntoAnExecutedPath_isDenied() {
        // The source path is not executed, so nothing is asked there; the
        // target is, and the document would run as its creator.
        when(repository.findById("d1")).thenReturn(Optional.of(doc("notes/x.yaml", "root", false)));

        assertThatThrownBy(() -> service.update("d1", null, null, null, SCHEDULER, WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
        verify(repository, never()).save(any(DocumentDocument.class));
    }

    @Test
    void movingAPrivilegedDocumentIntoAnExecutedPath_checksItsRunAs() {
        DocumentDocument d = doc("notes/s.yaml", "alice", true);
        when(repository.findById("d1")).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service.update("d1", null, null, "runAs: root", SCHEDULER, WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void aMove_asksCreateAtTheTarget() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc("notes/a.md", "alice", false)));

        service.update("d1", null, null, null, "_vance/recipes/a.yaml", WriteActor.user(ALICE));

        verify(permissionService)
                .enforce(
                        ALICE,
                        new Resource.Document("acme", "proj", "_vance/recipes/a.yaml"),
                        Action.CREATE,
                        WriteReason.USER);
    }

    @Test
    void aMoveTheTargetRefuses_doesNotHappen() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc("notes/a.md", "alice", false)));
        Resource target = new Resource.Document("acme", "proj", "_vance/recipes/a.yaml");
        doThrow(new PermissionDeniedException(ALICE, target, Action.CREATE))
                .when(permissionService)
                .enforce(ALICE, target, Action.CREATE, WriteReason.USER);

        assertThatThrownBy(
                        () -> service.update("d1", null, null, null, "_vance/recipes/a.yaml", WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
        verify(repository, never()).save(any(DocumentDocument.class));
    }

    @Test
    void movingAPrivilegedDocument_asksAdminAtTheTarget_underTheUserReason() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc("notes/s.yaml", "alice", true)));

        service.update("d1", null, null, null, "docs/s.yaml", WriteActor.system(ALICE));

        verify(permissionService)
                .enforce(ALICE, new Resource.Document("acme", "proj", "docs/s.yaml"), Action.ADMIN, WriteReason.USER);
    }

    @Test
    void restoringFromTrashIntoAnExecutedPath_checksTheTarget() {
        DocumentDocument trashed = doc(DocumentService.TRASH_FOLDER_PREFIX + "abc_x.yaml", "root", false);
        when(repository.findById("d1")).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.restore("d1", SCHEDULER, WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
        verify(repository, never()).save(any(DocumentDocument.class));
    }

    // ── the remaining content funnels ───────────────────────────────────────

    @Test
    void binaryReplaceOfAStrongerUsersScheduler_isDenied() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "root", false)));

        assertThatThrownBy(() -> service.replaceBinaryContent(
                        "d1",
                        null,
                        "cron: x".getBytes(StandardCharsets.UTF_8),
                        "alice",
                        DocumentService.TOOL_IDENTITY,
                        WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void binaryReplace_checksTheRunAsOfTheNewBytes() {
        when(repository.findById("d1")).thenReturn(Optional.of(doc(SCHEDULER, "alice", true)));

        assertThatThrownBy(() -> service.replaceBinaryContent(
                        "d1",
                        null,
                        "runAs: root".getBytes(StandardCharsets.UTF_8),
                        "alice",
                        DocumentService.TOOL_IDENTITY,
                        WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void restoringAnOldVersion_checksTheRunAsItDeclares() {
        DocumentDocument live = doc(SCHEDULER, "alice", true);
        live.setLineageId("lin-1");
        when(repository.findById("d1")).thenReturn(Optional.of(live));
        DocumentArchiveDocument archive =
                DocumentArchiveDocument.builder().id("a1").lineageId("lin-1").build();
        when(archiveService.findById("a1")).thenReturn(Optional.of(archive));
        when(archiveService.loadContent(archive))
                .thenReturn(new ByteArrayInputStream("runAs: root".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.restoreArchive("d1", "a1", WriteActor.user(ALICE)))
                .isInstanceOf(PermissionDeniedException.class);
        verify(archiveService, never()).archiveCurrent(any());
    }
}
