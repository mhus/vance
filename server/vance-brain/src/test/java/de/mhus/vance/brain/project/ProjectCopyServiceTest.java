package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.projects.ProjectCopyReportDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A copy is a <em>selection</em>, so what these tests pin down is mostly what
 * does <em>not</em> travel — the part that has no other alarm: a document
 * copied out of {@code _ext/} lands under a foreign id, a secret copied
 * without being asked for is a credential nobody decided to hand over, and a
 * copy left RUNNING starts firing the original's timers.
 */
class ProjectCopyServiceTest {

    private static final String TENANT = "acme";
    private static final String SOURCE = "alpha";
    private static final String TARGET = "beta";

    private ProjectService projectService;
    private ProjectLifecycleService lifecycleService;
    private DocumentService documentService;
    private SettingService settingService;
    private ProjectCopyService service;

    private final SecurityContext subject =
            SecurityContext.user("marvin", TENANT, List.of());

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        lifecycleService = mock(ProjectLifecycleService.class);
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        service = new ProjectCopyService(
                projectService, lifecycleService, documentService, settingService);

        when(projectService.findByTenantAndName(TENANT, SOURCE))
                .thenReturn(Optional.of(project(SOURCE, ProjectKind.NORMAL, ProjectStatus.RUNNING)));
        when(projectService.findByTenantAndName(TENANT, TARGET))
                .thenReturn(Optional.of(project(TARGET, ProjectKind.NORMAL, ProjectStatus.RUNNING)));
        when(documentService.listByProject(TENANT, SOURCE)).thenReturn(List.of());
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, SOURCE))
                .thenReturn(List.of());
        when(documentService.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));
        when(documentService.create(any(), any(), any(), any(), any(), any(), any(), any(),
                any(WriteActor.class)))
                .thenAnswer(inv -> {
                    DocumentDocument created = new DocumentDocument();
                    created.setId("new-" + inv.getArgument(2));
                    return created;
                });
    }

    @Test
    void copy_mountedTrashAndLogDocuments_areExcludedNotCopied() {
        when(documentService.listByProject(TENANT, SOURCE)).thenReturn(List.of(
                doc("documents/notes.md"),
                doc("_ext/library/paper.pdf"),
                doc("_vance/trash/abc_old.md"),
                doc("_vance/logs/run-1.log"),
                doc("_vance/config/mounts/library.yaml")));

        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        assertThat(report.getDocumentsCopied()).isEqualTo(2);
        assertThat(report.getDocumentsExcluded()).isEqualTo(3);
        // The mount *configuration* travels — the copy re-materialises the
        // mount itself the first time somebody lists it.
        verify(documentService).create(eq(TENANT), eq(TARGET), eq("_vance/config/mounts/library.yaml"),
                any(), any(), any(), any(), any(), any(WriteActor.class));
        verify(documentService, never()).create(any(), any(), eq("_ext/library/paper.pdf"),
                any(), any(), any(), any(), any(), any(WriteActor.class));
    }

    @Test
    void copy_documentThatFails_doesNotAbortTheRest() {
        when(documentService.listByProject(TENANT, SOURCE))
                .thenReturn(List.of(doc("a.md"), doc("b.md")));
        when(documentService.loadContent(any(DocumentDocument.class)))
                .thenThrow(new IllegalStateException("storage gone"))
                .thenAnswer(inv -> new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));

        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        assertThat(report.getDocumentsCopied()).isEqualTo(1);
        assertThat(report.getDocumentsFailed()).isEqualTo(1);
        assertThat(report.getFailures()).singleElement().asString().contains("storage gone");
    }

    @Test
    void copy_withoutSecretOptIn_namesTheSkippedKeysInsteadOfCopyingThem() {
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, SOURCE))
                .thenReturn(List.of(
                        setting("chat.language", "de", SettingType.STRING),
                        setting("ai.provider.main.apiKey", "cipher", SettingType.PASSWORD),
                        setting("smtp.pass", "cipher", SettingType.HIDDEN)));

        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        assertThat(report.getSettingsCopied()).isEqualTo(1);
        assertThat(report.getSecretsCopied()).isZero();
        assertThat(report.getSecretsSkipped())
                .containsExactly("ai.provider.main.apiKey", "smtp.pass");
        verify(settingService, never()).setEncryptedSecretAs(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void copy_withSecretOptIn_reEncryptsIntoTheNewScope() {
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, SOURCE))
                .thenReturn(List.of(setting("smtp.pass", "cipher", SettingType.PASSWORD)));
        when(settingService.getDecryptedPassword(
                TENANT, SettingService.SCOPE_PROJECT, SOURCE, "smtp.pass"))
                .thenReturn("hunter2");

        ProjectCopyReportDto report = copy(/*includeSecrets*/ true);

        assertThat(report.getSecretsCopied()).isEqualTo(1);
        assertThat(report.getSecretsSkipped()).isEmpty();
        verify(settingService).setEncryptedSecretAs(TENANT, SettingService.SCOPE_PROJECT,
                TARGET, "smtp.pass", "hunter2", SettingType.PASSWORD, "marvin");
    }

    @Test
    void copy_secretThatCannotBeDecrypted_isReportedRatherThanWrittenEmpty() {
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, SOURCE))
                .thenReturn(List.of(setting("smtp.pass", "cipher", SettingType.PASSWORD)));
        when(settingService.getDecryptedPassword(
                TENANT, SettingService.SCOPE_PROJECT, SOURCE, "smtp.pass"))
                .thenReturn(null);

        ProjectCopyReportDto report = copy(/*includeSecrets*/ true);

        assertThat(report.getSecretsCopied()).isZero();
        assertThat(report.getSecretsSkipped()).singleElement().asString()
                .contains("smtp.pass");
        verify(settingService, never()).setEncryptedSecretAs(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void copy_runningCopy_isSuspendedSoCopiedSchedulersDoNotFire() {
        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        verify(lifecycleService).suspend(TENANT, TARGET);
        assertThat(report.getStatusNote()).contains("SUSPENDED");
    }

    /**
     * A cluster with no room leaves the project created but unplaced — it
     * never reaches RUNNING, so nothing there fires and there is nothing to
     * suspend. Suspending it anyway would drive a status transition the
     * lifecycle does not expect from that state.
     */
    @Test
    void copy_targetNeverReachedRunning_isLeftAloneWithoutAStatusNote() {
        when(projectService.findByTenantAndName(TENANT, TARGET))
                .thenReturn(Optional.of(project(TARGET, ProjectKind.NORMAL, ProjectStatus.INIT)));

        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        verify(lifecycleService, never()).suspend(any(), any());
        assertThat(report.getStatusNote()).isNull();
    }

    @Test
    void copy_systemProject_isRefusedBeforeAnythingIsCreated() {
        when(projectService.findByTenantAndName(TENANT, SOURCE))
                .thenReturn(Optional.of(project(SOURCE, ProjectKind.SYSTEM, ProjectStatus.RUNNING)));

        assertThatThrownBy(() -> copy(/*includeSecrets*/ false))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);
        verify(lifecycleService, never()).create(any(), any(), any(), any(), anyList(),
                any(), any());
    }

    @Test
    void copy_permissionGrantsAndHistory_areNamedInTheReport() {
        ProjectCopyReportDto report = copy(/*includeSecrets*/ false);

        // A count of 0 would be indistinguishable from "there was nothing".
        assertThat(report.getNotCopied()).anyMatch(line -> line.contains("permission grants"));
        assertThat(report.getNotCopied()).anyMatch(line -> line.contains("inbox threads"));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private ProjectCopyReportDto copy(boolean includeSecrets) {
        return service.copy(TENANT, SOURCE, TARGET, null, null, includeSecrets, subject);
    }

    private static ProjectDocument project(String name, ProjectKind kind, ProjectStatus status) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setKind(kind);
        doc.setStatus(status);
        return doc;
    }

    private static DocumentDocument doc(String path) {
        DocumentDocument d = new DocumentDocument();
        d.setTenantId(TENANT);
        d.setProjectId(SOURCE);
        d.setPath(path);
        d.setMimeType("text/markdown");
        return d;
    }

    private static SettingDocument setting(String key, String value, SettingType type) {
        SettingDocument s = new SettingDocument();
        s.setTenantId(TENANT);
        s.setReferenceType(SettingService.SCOPE_PROJECT);
        s.setReferenceId(SOURCE);
        s.setKey(key);
        s.setValue(value);
        s.setType(type);
        return s;
    }
}
