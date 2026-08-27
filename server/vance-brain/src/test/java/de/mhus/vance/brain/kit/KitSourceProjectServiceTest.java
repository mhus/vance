package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitSourceProjectDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a picker is allowed to show. Three exclusions carry the weight: a
 * project the caller cannot read (the list must not disclose it), a kit that
 * refuses a direct install (offering it means offering a failure), and — the
 * one that is <em>not</em> an exclusion — an unfinished kit, which is the whole
 * reason to install from a project rather than from git.
 */
class KitSourceProjectServiceTest {

    private static final String TENANT = "acme";

    private DocumentService documentService;
    private KitRecordStore recordStore;
    private ProjectService projectService;
    private PermissionService permissionService;
    private KitSourceProjectService service;

    private final SecurityContext marvin =
            SecurityContext.user("marvin", TENANT, List.of());

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        recordStore = mock(KitRecordStore.class);
        projectService = mock(ProjectService.class);
        permissionService = mock(PermissionService.class);
        service = new KitSourceProjectService(
                documentService, recordStore, projectService, permissionService);

        when(permissionService.check(any(), any(), any())).thenReturn(true);
    }

    @Test
    void list_reportsKitNameAndAReadyMadeProjectUrl() {
        given("kit-dev", "acme-onboarding", ProjectKind.NORMAL, null);

        List<KitSourceProjectDto> found = service.list(TENANT, marvin);

        assertThat(found).singleElement().satisfies(entry -> {
            assertThat(entry.getKitName()).isEqualTo("acme-onboarding");
            assertThat(entry.getProjectId()).isEqualTo("kit-dev");
            // Built server-side so the scheme is spelled in one place.
            assertThat(entry.getSourceUrl()).isEqualTo("project:kit-dev");
        });
    }

    @Test
    void list_projectTheCallerCannotRead_isLeftOut() {
        given("secret-dev", "internal-kit", ProjectKind.NORMAL, null);
        when(permissionService.check(
                eq(marvin), eq(new Resource.Project(TENANT, "secret-dev")), eq(Action.READ)))
                .thenReturn(false);

        assertThat(service.list(TENANT, marvin)).isEmpty();
    }

    @Test
    void list_abstractBaseKit_isLeftOutBecauseADirectInstallWouldFail() {
        given("base-dev", "base-prompts", ProjectKind.NORMAL,
                KitDescriptorDto.builder().installable(false).build());

        assertThat(service.list(TENANT, marvin)).isEmpty();
    }

    @Test
    void list_tuningArtifact_isLeftOut() {
        given("tune-dev", "lora-bundle", ProjectKind.NORMAL,
                KitDescriptorDto.builder().artifact(true).build());

        assertThat(service.list(TENANT, marvin)).isEmpty();
    }

    @Test
    void list_unfinishedKit_isStillOffered() {
        // The case that decides against a draft/published flag: trying out a
        // kit before it is pushed is the reason this source type exists, so a
        // filter that hid work in progress would hide the point.
        given("wip-dev", "half-done", ProjectKind.NORMAL,
                KitDescriptorDto.builder().version(null).build());

        assertThat(service.list(TENANT, marvin))
                .extracting(KitSourceProjectDto::getKitName)
                .containsExactly("half-done");
    }

    @Test
    void list_projectPromotedBeforeTheDescriptorWasADocument_isStillOffered() {
        // No descriptor at all: the manifest is the statement that matters, and
        // hiding a working source over a missing file would be worse than
        // letting the next update write it.
        given("legacy-dev", "old-kit", ProjectKind.NORMAL, null);

        assertThat(service.list(TENANT, marvin)).hasSize(1);
    }

    @Test
    void list_systemProject_isLeftOut() {
        given("_vance", "system-kit", ProjectKind.SYSTEM, null);

        assertThat(service.list(TENANT, marvin)).isEmpty();
    }

    @Test
    void list_unparseableManifest_isLeftOutRatherThanListedAsBroken() {
        marker("broken-dev");
        when(projectService.findByTenantAndName(TENANT, "broken-dev"))
                .thenReturn(Optional.of(project("broken-dev", ProjectKind.NORMAL)));
        // loadManifest returns null for a malformed document, having logged it.
        when(recordStore.loadManifest(TENANT, "broken-dev")).thenReturn(null);

        assertThat(service.list(TENANT, marvin)).isEmpty();
    }

    @Test
    void list_sortedByKitName() {
        when(documentService.findByPathAcrossProjects(TENANT, KitRecordStore.MANIFEST_PATH))
                .thenReturn(List.of(marker("z-dev"), marker("a-dev")));
        stub("z-dev", "zeta", ProjectKind.NORMAL, null);
        stub("a-dev", "alpha", ProjectKind.NORMAL, null);

        assertThat(service.list(TENANT, marvin))
                .extracting(KitSourceProjectDto::getKitName)
                .containsExactly("alpha", "zeta");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private void given(String projectId, String kitName, ProjectKind kind,
            KitDescriptorDto descriptor) {
        when(documentService.findByPathAcrossProjects(TENANT, KitRecordStore.MANIFEST_PATH))
                .thenReturn(List.of(marker(projectId)));
        stub(projectId, kitName, kind, descriptor);
    }

    private void stub(String projectId, String kitName, ProjectKind kind,
            KitDescriptorDto descriptor) {
        when(projectService.findByTenantAndName(TENANT, projectId))
                .thenReturn(Optional.of(project(projectId, kind)));
        when(recordStore.loadManifest(TENANT, projectId)).thenReturn(manifest(kitName));
        when(recordStore.loadDescriptor(TENANT, projectId)).thenReturn(descriptor);
    }

    private static DocumentDocument marker(String projectId) {
        DocumentDocument doc = new DocumentDocument();
        doc.setTenantId(TENANT);
        doc.setProjectId(projectId);
        doc.setPath(KitRecordStore.MANIFEST_PATH);
        return doc;
    }

    private static ProjectDocument project(String name, ProjectKind kind) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setTitle(name + " title");
        doc.setKind(kind);
        return doc;
    }

    private static KitManifestDto manifest(String kitName) {
        return KitManifestDto.builder()
                .kit(KitMetadataDto.builder()
                        .name(kitName)
                        .description("desc")
                        .version("1.0.0")
                        .build())
                .build();
    }
}
