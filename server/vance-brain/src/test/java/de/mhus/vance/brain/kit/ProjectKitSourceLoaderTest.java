package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project source is the only one that lives <em>inside</em> the deployment,
 * so what these tests pin down is the part no other loader has to answer:
 * whether the person asking may read the source project, and what happens to
 * its credentials on the way.
 */
class ProjectKitSourceLoaderTest {

    private static final String TENANT = "acme";
    private static final String SOURCE = "kit-authoring";
    private static final String TARGET = "consumer";

    private ProjectService projectService;
    private KitRecordStore recordStore;
    private KitTreeWriter treeWriter;
    private PermissionService permissionService;
    private SecurityContextFactory contextFactory;
    private ProjectKitSourceLoader loader;

    private final SecurityContext marvin =
            SecurityContext.user("marvin", TENANT, List.of());

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        recordStore = mock(KitRecordStore.class);
        treeWriter = mock(KitTreeWriter.class);
        permissionService = mock(PermissionService.class);
        contextFactory = mock(SecurityContextFactory.class);
        loader = new ProjectKitSourceLoader(
                projectService, recordStore, treeWriter, permissionService, contextFactory);

        when(projectService.findByTenantAndName(TENANT, SOURCE))
                .thenReturn(Optional.of(project(SOURCE, ProjectKind.NORMAL)));
        when(recordStore.loadManifest(TENANT, SOURCE)).thenReturn(manifest());
        when(contextFactory.forToolSubject(TENANT, "marvin")).thenReturn(marvin);
        // The writer is what actually produces kit.yaml, and the loader reads
        // it back — so the stub has to leave a tree behind, not just a return
        // value.
        when(treeWriter.write(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    writeMinimalTree(inv.getArgument(3));
                    return new KitTreeWriter.Written(
                            List.of("documents/notes.md"), List.of("chat.language"), List.of());
                });
    }

    @Test
    void load_enforcesReadOnTheSourceProject(@TempDir Path target) {
        loader.load(source("project:" + SOURCE), config(), access(), target);

        verify(permissionService).enforce(
                marvin, new Resource.Project(TENANT, SOURCE), Action.READ);
    }

    @Test
    void load_withoutReadOnTheSourceProject_isRefused(@TempDir Path target) {
        // The whole reason this loader authorizes at all: installing a kit
        // into a project you own must not become a way to read a project you
        // do not.
        doThrow(new PermissionDeniedException(
                marvin, new Resource.Project(TENANT, SOURCE), Action.READ))
                .when(permissionService).enforce(any(), any(), any());

        assertThatThrownBy(() -> loader.load(source("project:" + SOURCE), config(), access(), target))
                .isInstanceOf(PermissionDeniedException.class);
        verify(treeWriter, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void load_projectThatIsNotAKitSource_saysSoAndNamesTheManifest(@TempDir Path target) {
        when(recordStore.loadManifest(TENANT, SOURCE)).thenReturn(null);

        assertThatThrownBy(() -> loader.load(source("project:" + SOURCE), config(), access(), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("is not a kit source")
                .hasMessageContaining(KitRecordStore.MANIFEST_PATH);
    }

    @Test
    void load_systemProject_isRefused(@TempDir Path target) {
        when(projectService.findByTenantAndName(TENANT, SOURCE))
                .thenReturn(Optional.of(project(SOURCE, ProjectKind.SYSTEM)));

        assertThatThrownBy(() -> loader.load(source("project:" + SOURCE), config(), access(), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    void load_withCopySecrets_carriesTheCiphertextRatherThanDecryptingIt(@TempDir Path target) {
        loader.load(source("project:" + SOURCE), config(), access(), target);

        verify(treeWriter).write(eq(TENANT), eq(SOURCE), any(), eq(target),
                eq(KitTreeWriter.SecretMode.SERVER), eq(null));
    }

    @Test
    void load_withoutCopySecrets_leavesCredentialsOut(@TempDir Path target) {
        loader.load(source("project:" + SOURCE), config(),
                access().withCopySecrets(false), target);

        verify(treeWriter).write(eq(TENANT), eq(SOURCE), any(), eq(target),
                eq(KitTreeWriter.SecretMode.SKIP), eq(null));
    }

    @Test
    void load_reportsATreeHashAsTheCommit(@TempDir Path target) {
        // A project has no revisions, but "has this changed since?" still
        // needs an answer for update to be meaningful.
        KitRepoLoader.LoadedKit loaded =
                loader.load(source("project:" + SOURCE), config(), access(), target);

        assertThat(loaded.commit()).isNotBlank();
        assertThat(loaded.descriptor().getName()).isEqualTo("authoring-kit");
    }

    @Test
    void load_withAPath_isRefusedRatherThanIgnored(@TempDir Path target) {
        // (url, path) is the identity of an installation, so a dropped path
        // would make one kit look like two.
        KitInheritDto withPath = source("project:" + SOURCE);
        withPath.setPath("subdir");

        assertThatThrownBy(() -> loader.load(withPath, config(), access(), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("takes no path");
    }

    /**
     * A commit is ignored, not refused — and this is the case that decides it:
     * {@code reapply} hands the recorded commit back on purpose ("write this
     * again as it was"), so refusing one would break reapply for every
     * project-sourced kit. A path is refused because it changes which kit is
     * meant; a commit names a version a project does not have.
     */
    @Test
    void load_withARecordedCommit_ignoresItRatherThanRefusing(@TempDir Path target) {
        KitInheritDto pinned = source("project:" + SOURCE);
        pinned.setCommit("f00dcafe");

        KitRepoLoader.LoadedKit loaded = loader.load(pinned, config(), access(), target);

        // The tree it wrote, not the value it was handed.
        assertThat(loaded.commit()).isNotEqualTo("f00dcafe");
    }

    @Test
    void load_intoItself_isRefused(@TempDir Path target) {
        assertThatThrownBy(() -> loader.load(
                source("project:" + TARGET), config(), access(), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("its own kit");
    }

    @Test
    void supports_onlyProject() {
        assertThat(loader.supports(KitSourceType.PROJECT)).isTrue();
        assertThat(loader.supports(KitSourceType.GIT)).isFalse();
        assertThat(loader.supports(KitSourceType.ODE)).isFalse();
    }

    @Test
    void projectNameOf_toleratesSlashesAfterTheSchemeButRefusesTwoSegments() {
        assertThat(ProjectKitSourceLoader.projectNameOf("project:alpha")).isEqualTo("alpha");
        assertThat(ProjectKitSourceLoader.projectNameOf(" project://alpha ")).isEqualTo("alpha");
        assertThatThrownBy(() -> ProjectKitSourceLoader.projectNameOf("project:"))
                .isInstanceOf(KitException.class);
        assertThatThrownBy(() -> ProjectKitSourceLoader.projectNameOf("project:a/b"))
                .isInstanceOf(KitException.class);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static KitInheritDto source(String url) {
        return KitInheritDto.builder().url(url).build();
    }

    private static KitSourceDto config() {
        return KitSourceDto.builder().id("(unconfigured)").type(KitSourceType.PROJECT).build();
    }

    private static KitAccess access() {
        return KitAccess.of(TENANT, TARGET).withActor("marvin");
    }

    private static ProjectDocument project(String name, ProjectKind kind) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setKind(kind);
        return doc;
    }

    private static KitManifestDto manifest() {
        return KitManifestDto.builder()
                .kit(KitMetadataDto.builder()
                        .name("authoring-kit")
                        .description("Kit authored in a project")
                        .version("1.0.0")
                        .build())
                .documents(List.of("documents/notes.md"))
                .settings(List.of("chat.language"))
                .build();
    }

    private static void writeMinimalTree(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("kit.yaml"), """
                name: authoring-kit
                description: Kit authored in a project
                version: 1.0.0
                """);
    }
}
