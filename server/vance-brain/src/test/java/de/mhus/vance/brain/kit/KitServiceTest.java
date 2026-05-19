package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the visibility-flag enforcement in
 * {@link KitService#importKit} — spec: kits.md §3.2 + §6.1/§6.2.
 *
 * <p>The pipeline up to {@code installer.apply} is exercised with
 * mocks; we only check that the right {@link KitException} is thrown
 * for the bad combinations and that {@code installer.apply} is invoked
 * (or skipped) accordingly.
 */
class KitServiceTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";

    private KitResolver resolver;
    private KitInstaller installer;
    private KitExporter exporter;
    private KitWorkspace workspace;
    private ProjectService projectService;
    private KitService service;

    @BeforeEach
    void setUp() {
        resolver = mock(KitResolver.class);
        installer = mock(KitInstaller.class);
        exporter = mock(KitExporter.class);
        workspace = mock(KitWorkspace.class);
        projectService = mock(ProjectService.class);

        // Project exists by default — flag tests don't care about
        // project lookup, they care about the post-resolve gate.
        when(projectService.findByTenantAndName(eq(TENANT), eq(PROJECT)))
                .thenReturn(Optional.of(mock(ProjectDocument.class)));

        // Sane default: no manifest in the project, so INSTALL/APPLY
        // pass the precondition checks. Tests that care about UPDATE
        // override this with a non-null manifest.
        when(installer.loadManifest(eq(TENANT), eq(PROJECT))).thenReturn(null);

        service = new KitService(resolver, installer, exporter, workspace, projectService,
                mock(TemplateApplier.class));
    }

    // ── installable=false ─────────────────────────────────────────────

    @Test
    void importKit_topLayerNotInstallable_rejectsInstall() {
        stubResolved(descriptor("base-kit").installable(false).build());

        assertThatThrownBy(() -> service.importKit(TENANT, importRequest(KitImportMode.INSTALL), null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("base-kit")
                .hasMessageContaining("installable=false");
        verify(installer, never()).apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void importKit_topLayerNotInstallable_rejectsApply() {
        stubResolved(descriptor("base-kit").installable(false).build());

        assertThatThrownBy(() -> service.importKit(TENANT, importRequest(KitImportMode.APPLY), null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("installable=false");
        verify(installer, never()).apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    // ── artifact=true ────────────────────────────────────────────────

    @Test
    void importKit_artifactKit_rejectsInstall() {
        stubResolved(descriptor("tuning-kit").artifact(true).build());

        assertThatThrownBy(() -> service.importKit(TENANT, importRequest(KitImportMode.INSTALL), null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("tuning-kit")
                .hasMessageContaining("artifact");
        verify(installer, never()).apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void importKit_artifactKit_rejectsUpdate() {
        // UPDATE requires an existing manifest; satisfy that precondition
        // so the test fails on the artifact gate, not on missing manifest.
        when(installer.loadManifest(eq(TENANT), eq(PROJECT))).thenReturn(stubManifest());
        stubResolved(descriptor("tuning-kit").artifact(true).build());

        assertThatThrownBy(() -> service.importKit(TENANT, importRequest(KitImportMode.UPDATE), null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("artifact");
        verify(installer, never()).apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void importKit_artifactKit_acceptsApply() {
        stubResolved(descriptor("tuning-kit").artifact(true).build());
        when(installer.apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(KitOperationResultDto.builder().build());

        assertThatCode(() -> service.importKit(TENANT, importRequest(KitImportMode.APPLY), null))
                .doesNotThrowAnyException();
        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.APPLY), anyBoolean(), anyBoolean(), any(), any());
    }

    // ── happy path ───────────────────────────────────────────────────

    @Test
    void importKit_normalKit_install_callsInstaller() {
        stubResolved(descriptor("normal-kit").build());
        when(installer.apply(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(KitOperationResultDto.builder().build());

        service.importKit(TENANT, importRequest(KitImportMode.INSTALL), null);

        verify(installer).apply(any(), any(), any(), any(), eq(KitImportMode.INSTALL), anyBoolean(), anyBoolean(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static KitDescriptorDto.KitDescriptorDtoBuilder descriptor(String name) {
        return KitDescriptorDto.builder().name(name).description("desc");
    }

    private static KitImportRequestDto importRequest(KitImportMode mode) {
        return KitImportRequestDto.builder()
                .projectId(PROJECT)
                .source(KitInheritDto.builder().url("file:///fake").build())
                .mode(mode)
                .build();
    }

    private static KitManifestDto stubManifest() {
        return KitManifestDto.builder()
                .kit(KitMetadataDto.builder().name("existing").description("d").build())
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
                new ArrayList<>());
        when(resolver.resolve(any(), any())).thenReturn(resolved);
    }
}
