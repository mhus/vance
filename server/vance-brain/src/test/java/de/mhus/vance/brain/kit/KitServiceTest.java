package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Visibility-flag enforcement and the multi-kit preconditions in
 * {@link KitService#importKit} — spec: kits.md §3.2,
 * {@code planning/kit-installed-multi.md} §2.1.
 *
 * <p>The pipeline up to {@code installer.apply} is exercised with
 * mocks; the assertions are about which {@link KitException} is thrown
 * and whether {@code installer.apply} runs at all.
 */
class KitServiceTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";
    private static final String SOURCE_URL = "file:///fake";

    private KitResolver resolver;
    private KitInstaller installer;
    private KitExporter exporter;
    private KitWorkspace workspace;
    private KitRecordStore recordStore;
    private ProjectService projectService;
    private KitStoreCredentials storeCredentials;
    private KitService service;

    @BeforeEach
    void setUp() {
        resolver = mock(KitResolver.class);
        installer = mock(KitInstaller.class);
        exporter = mock(KitExporter.class);
        workspace = mock(KitWorkspace.class);
        recordStore = mock(KitRecordStore.class);
        projectService = mock(ProjectService.class);

        // Project exists by default — the flag tests care about the
        // post-resolve gate, not about project lookup.
        when(projectService.findByTenantAndName(eq(TENANT), eq(PROJECT)))
                .thenReturn(Optional.of(mock(ProjectDocument.class)));

        // Nothing installed, no config document: the ordinary starting point.
        when(recordStore.find(anyString(), anyString(), anyString())).thenReturn(null);
        when(recordStore.findByOrigin(anyString(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(recordStore.list(anyString(), anyString())).thenReturn(new ArrayList<>());
        when(recordStore.listInLayerOrder(anyString(), anyString())).thenReturn(new ArrayList<>());
        when(recordStore.loadConfig(anyString(), anyString(), anyString()))
                .thenReturn(KitConfigDto.builder().build());
        when(recordStore.loadManifest(anyString(), anyString())).thenReturn(null);

        storeCredentials = mock(KitStoreCredentials.class);
        when(storeCredentials.resolve(any(), any(), any(), any(), any()))
                .thenReturn(KitAccess.of(TENANT));

        service = new KitService(resolver, installer, exporter, workspace, recordStore,
                mock(KitLegacyMigrator.class), projectService, mock(TemplateApplier.class),
                storeCredentials);
    }

    // ── installable=false ─────────────────────────────────────────────

    @Test
    void importKit_topLayerNotInstallable_rejectsInstall() {
        stubResolved(descriptor("base-kit").installable(false).build());

        assertThatThrownBy(() -> service.importKit(
                TENANT, importRequest(KitImportMode.INSTALL), null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("base-kit")
                .hasMessageContaining("installable=false");
        verifyInstallerNeverRan();
    }

    @Test
    void importKit_topLayerNotInstallable_rejectsApply() {
        stubResolved(descriptor("base-kit").installable(false).build());

        assertThatThrownBy(() -> service.importKit(
                TENANT, importRequest(KitImportMode.APPLY), null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("installable=false");
        verifyInstallerNeverRan();
    }

    // ── artifact=true ────────────────────────────────────────────────

    @Test
    void importKit_artifactKit_installsWithoutManifest() {
        // A tuning bundle is now perfectly installable — the flag only
        // says it must not become the project's kit *source*.
        stubResolved(descriptor("tuning-kit").artifact(true).build());
        stubInstallerResult();

        assertThatCode(() -> service.importKit(
                TENANT, importRequest(KitImportMode.INSTALL), null, SettingWriteOrigin.USER))
                .doesNotThrowAnyException();
        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.INSTALL),
                anyBoolean(), anyBoolean(), any(), eq(false), any(), any());
    }

    @Test
    void importKit_artifactKit_rejectsAuthoringManifest() {
        stubResolved(descriptor("tuning-kit").artifact(true).build());

        KitImportRequestDto request = importRequest(KitImportMode.INSTALL);
        request.setWriteManifest(true);

        assertThatThrownBy(() -> service.importKit(TENANT, request, null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("tuning-kit")
                .hasMessageContaining("artifact");
        verifyInstallerNeverRan();
    }

    @Test
    void importKit_artifactKit_acceptsApply() {
        stubResolved(descriptor("tuning-kit").artifact(true).build());
        stubInstallerResult();

        assertThatCode(() -> service.importKit(
                TENANT, importRequest(KitImportMode.APPLY), null, SettingWriteOrigin.USER))
                .doesNotThrowAnyException();
        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.APPLY),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    // ── multi-kit preconditions ──────────────────────────────────────

    @Test
    void importKit_install_sameSourceTwice_rejectsWithUpdateHint() {
        stubResolved(descriptor("normal-kit").build());
        String recordId = KitRecordId.of("normal-kit", SOURCE_URL, null);
        when(recordStore.findByOrigin(TENANT, PROJECT, SOURCE_URL, null))
                .thenReturn(record("normal-kit", recordId));

        assertThatThrownBy(() -> service.importKit(
                TENANT, importRequest(KitImportMode.INSTALL), null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("already installed")
                .hasMessageContaining("update");
        verifyInstallerNeverRan();
    }

    @Test
    void importKit_install_afterTheKitRenamedItself_isStillRefused() {
        // Identity is (url, path), so a new name does not make a new kit.
        // Looking the record up by derived id would miss it here, let the
        // INSTALL through, and silently perform an update instead — the
        // surprise re-write the guard exists to prevent.
        stubResolved(descriptor("renamed-kit").build());
        when(recordStore.findByOrigin(TENANT, PROJECT, SOURCE_URL, null))
                .thenReturn(record("the-old-name", "the-old-name-abc123"));

        assertThatThrownBy(() -> service.importKit(
                TENANT, importRequest(KitImportMode.INSTALL), null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("already installed");
        verifyInstallerNeverRan();
    }

    @Test
    void importKit_install_secondKitFromDifferentSource_isAllowed() {
        // Multi-kit is the whole point: an unrelated kit already being
        // installed must not block this one.
        stubResolved(descriptor("normal-kit").build());
        stubInstallerResult();
        when(recordStore.list(TENANT, PROJECT))
                .thenReturn(List.of(record("other-kit", "other-kit-abc123")));

        assertThatCode(() -> service.importKit(
                TENANT, importRequest(KitImportMode.INSTALL), null, SettingWriteOrigin.USER))
                .doesNotThrowAnyException();
        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.INSTALL),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    @Test
    void updateInstalled_unknownKit_failsWithoutResolving() {
        assertThatThrownBy(() -> service.updateInstalled(
                TENANT, PROJECT, "ghost-000000", false, null, null, null, SettingWriteOrigin.USER))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("ghost-000000");
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void uninstall_unknownKit_fails() {
        assertThatThrownBy(() -> service.uninstall(TENANT, PROJECT, "ghost-000000", false))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("ghost-000000");
    }

    // ── user config ──────────────────────────────────────────────────

    @Test
    void loadConfig_unknownKit_fails() {
        assertThatThrownBy(() -> service.loadConfig(TENANT, PROJECT, "ghost-000000"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("ghost-000000");
    }

    @Test
    void saveConfig_unknownKit_doesNotWrite() {
        // Writing config for a kit that is not installed would leave an
        // orphan document the UI never shows again.
        assertThatThrownBy(() -> service.saveConfig(
                TENANT, PROJECT, "ghost-000000", KitConfigDto.builder().build(), null))
                .isInstanceOf(KitException.class);
        verify(recordStore, never()).saveConfig(any(), any(), any(), any(), any());
    }

    @Test
    void saveConfig_installedKit_writesBesideTheRecord() {
        String recordId = "normal-kit-abc123";
        when(recordStore.find(TENANT, PROJECT, recordId)).thenReturn(record("normal-kit", recordId));
        KitConfigDto config = KitConfigDto.builder().sortIndex(20).build();

        service.saveConfig(TENANT, PROJECT, recordId, config, "hummel");

        verify(recordStore).saveConfig(TENANT, PROJECT, recordId, config, "hummel");
        // The install record must stay untouched — that separation is the
        // entire point of keeping config in its own document.
        verify(recordStore, never()).save(any(), any(), any(), any());
    }

    // ── authoring promote ────────────────────────────────────────────

    @Test
    void promoteToAuthoring_projectAlreadyIsAKitSource_refuses() {
        String recordId = "normal-kit-abc123";
        when(recordStore.find(TENANT, PROJECT, recordId)).thenReturn(record("normal-kit", recordId));
        when(recordStore.loadManifest(TENANT, PROJECT)).thenReturn(
                de.mhus.vance.api.kit.KitManifestDto.builder()
                        .kit(KitMetadataDto.builder().name("existing").description("d").build())
                        .build());

        assertThatThrownBy(() -> service.promoteToAuthoring(TENANT, PROJECT, recordId, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("existing");
    }

    @Test
    void promoteToAuthoring_artifactKit_refuses() {
        String recordId = "tuning-kit-abc123";
        KitInstalledRecordDto rec = record("tuning-kit", recordId);
        rec.setDescriptor(descriptor("tuning-kit").artifact(true).build());
        when(recordStore.find(TENANT, PROJECT, recordId)).thenReturn(rec);

        assertThatThrownBy(() -> service.promoteToAuthoring(TENANT, PROJECT, recordId, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("artifact");
    }

    // ── happy path ───────────────────────────────────────────────────

    @Test
    void importKit_normalKit_install_callsInstaller() {
        stubResolved(descriptor("normal-kit").build());
        stubInstallerResult();

        service.importKit(TENANT, importRequest(KitImportMode.INSTALL), null,
                SettingWriteOrigin.USER);

        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.INSTALL),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void verifyInstallerNeverRan() {
        verify(installer, never()).apply(any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    private void stubInstallerResult() {
        when(installer.apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(KitOperationResultDto.builder().build());
    }

    private static KitDescriptorDto.KitDescriptorDtoBuilder descriptor(String name) {
        return KitDescriptorDto.builder().name(name).description("desc");
    }

    private static KitImportRequestDto importRequest(KitImportMode mode) {
        return KitImportRequestDto.builder()
                .projectId(PROJECT)
                .source(KitInheritDto.builder().url(SOURCE_URL).build())
                .mode(mode)
                .build();
    }

    private static KitInstalledRecordDto record(String name, String id) {
        return KitInstalledRecordDto.builder()
                .id(id)
                .kit(KitMetadataDto.builder().name(name).description("d").build())
                .origin(KitOriginDto.builder().url(SOURCE_URL).build())
                .artefacts(KitArtefactsDto.builder().build())
                .build();
    }

    private void stubResolved(KitDescriptorDto descriptor) {
        Path buildRoot = Paths.get("/tmp/fake-build-root");
        KitResolver.ResolvedKit resolved = new KitResolver.ResolvedKit(
                buildRoot,
                descriptor,
                "deadbeef",
                new ArrayList<>(),
                KitResolver.LayerArtefacts.empty(),
                new LinkedHashMap<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                de.mhus.vance.api.kit.KitSignatureStatus.UNSIGNED,
                "test-source");
        when(resolver.resolve(any(), any())).thenReturn(resolved);
    }
}
