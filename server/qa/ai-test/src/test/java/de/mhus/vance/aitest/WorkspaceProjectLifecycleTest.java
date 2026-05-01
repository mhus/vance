package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.model.Filters;
import de.mhus.vance.brain.VanceBrainApplication;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * End-to-end exercise of {@link ProjectLifecycleService} +
 * {@link WorkspaceService}: bring → temp-RootDir → suspend → recover →
 * close. Uses direct Spring beans (no foot subprocess, no LLM) so the
 * mechanics are verified without timing dependencies on chat turns.
 *
 * <p>Each test uses a unique project name so the cases don't interfere
 * inside the shared brain context.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceProjectLifecycleTest {

    private static final String TENANT = "acme";
    /** Group all closed projects need to be moved into. Seeded by BootstrapBrainService. */
    private static final String CLOSED_GROUP = "archived";

    @Autowired
    MongoTemplate mongo;

    @Autowired
    ProjectService projectService;

    @Autowired
    ProjectLifecycleService lifecycleService;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    WorkspaceProperties workspaceProperties;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @Test
    void bring_freshProject_transitionsThroughRecovering_toRunning() {
        String name = uniqueName("bring");
        projectService.create(TENANT, name, "bring test", null, List.of());

        ProjectDocument before = projectService.findByTenantAndName(TENANT, name).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(ProjectStatus.INIT);
        assertThat(before.getPodIp()).isNull();

        ProjectDocument after = lifecycleService.bring(TENANT, name);
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        assertThat(after.getPodIp())
                .as("bring should claim the project for this pod")
                .isNotNull();

        Path workspaceFolder = projectFolder(name);
        assertThat(Files.isDirectory(workspaceFolder))
                .as("workspace folder should exist on disk after bring")
                .isTrue();
        assertThat(Files.exists(workspaceFolder.resolve("workspace.json")))
                .as("workspace.json marker file should be written on first init")
                .isTrue();

        // Idempotent: second bring returns RUNNING and refreshes the claim.
        ProjectDocument again = lifecycleService.bring(TENANT, name);
        assertThat(again.getStatus()).isEqualTo(ProjectStatus.RUNNING);
    }

    @Test
    void tempFile_createsTempRootDir_withSiblingDescriptor() throws Exception {
        String name = uniqueName("temp");
        projectService.create(TENANT, name, "temp file test", null, List.of());
        lifecycleService.bring(TENANT, name);

        String creator = "p-fake-process-1";
        Path tempFile = workspaceService.createTempFile(name, creator, "vance", ".txt");
        Files.writeString(tempFile, "hello workspace");

        Path workspaceFolder = projectFolder(name);
        assertThat(tempFile.startsWith(workspaceFolder))
                .as("temp file must live under the project's workspace root")
                .isTrue();

        // Exactly one RootDir + sibling descriptor for the lazy temp dir.
        List<RootDirHandle> roots = workspaceService.listRootDirs(name);
        assertThat(roots).hasSize(1);
        RootDirHandle handle = roots.get(0);
        assertThat(handle.getType()).isEqualTo("temp");
        assertThat(handle.deleteOnCreatorClose()).isTrue();
        assertThat(handle.creatorProcessId()).isEqualTo(creator);

        Path descriptor = workspaceFolder.resolve(handle.getDirName() + ".json");
        assertThat(Files.exists(descriptor))
                .as("sibling descriptor JSON must exist next to the RootDir folder")
                .isTrue();

        // disposeByCreator removes the lazy temp RootDir + descriptor.
        workspaceService.disposeByCreator(name, creator);
        assertThat(workspaceService.listRootDirs(name))
                .as("disposeByCreator should drop the lazy temp RootDir")
                .isEmpty();
        assertThat(Files.exists(descriptor)).isFalse();
    }

    @Test
    void suspend_then_bring_recoversFromMongoSnapshot() throws Exception {
        String name = uniqueName("susp");
        projectService.create(TENANT, name, "suspend test", null, List.of());
        lifecycleService.bring(TENANT, name);

        // A persistent worker-style RootDir (not deleteOnCreatorClose) survives suspend/recover.
        RootDirSpec spec = RootDirSpec.builder()
                .projectId(name)
                .type("temp")                // git would need a remote, temp suffices for the snapshot round-trip
                .creatorProcessId("p-persistent")
                .labelHint("scratchpad")
                .deleteOnCreatorClose(false)
                .build();
        RootDirHandle handle = workspaceService.createRootDir(spec);
        Path scratchFile = handle.getPath().resolve("note.txt");
        Files.writeString(scratchFile, "ephemeral content");

        // ─── Suspend ───
        ProjectDocument suspended = lifecycleService.suspend(TENANT, name);
        assertThat(suspended.getStatus()).isEqualTo(ProjectStatus.SUSPENDED);

        Path workspaceFolder = projectFolder(name);
        assertThat(Files.exists(workspaceFolder))
                .as("workspace folder must be removed on suspend")
                .isFalse();

        long snapshotCount = mongo.getCollection("workspace_snapshots")
                .countDocuments(Filters.eq("projectId", name));
        assertThat(snapshotCount)
                .as("each suspended RootDir should yield a snapshot in workspace_snapshots")
                .isEqualTo(1L);

        // ─── Bring again — auto-recover via init ───
        ProjectDocument resumed = lifecycleService.bring(TENANT, name);
        assertThat(resumed.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        assertThat(Files.isDirectory(workspaceFolder))
                .as("workspace folder must be recreated on bring-after-suspend")
                .isTrue();

        // Temp content does NOT persist across suspend (by design).
        // The RootDir itself is back, but its content is empty — the
        // worker would have had to import note.txt as a Document to keep it.
        List<RootDirHandle> rootsAfter = workspaceService.listRootDirs(name);
        assertThat(rootsAfter).hasSize(1);
        assertThat(rootsAfter.get(0).getDirName()).isEqualTo(handle.getDirName());
        assertThat(Files.exists(scratchFile))
                .as("temp content must NOT survive suspend — see workspace-management.md §5.1")
                .isFalse();

        long snapshotsAfter = mongo.getCollection("workspace_snapshots")
                .countDocuments(Filters.eq("projectId", name));
        assertThat(snapshotsAfter)
                .as("recover should consume snapshots after applying them")
                .isZero();
    }

    @Test
    void close_disposesWorkspace_andTransitionsToCLOSED() {
        String name = uniqueName("close");
        projectService.create(TENANT, name, "close test", null, List.of());
        lifecycleService.bring(TENANT, name);

        Path workspaceFolder = projectFolder(name);
        assertThat(Files.isDirectory(workspaceFolder)).isTrue();

        ProjectDocument closed = lifecycleService.close(TENANT, name, CLOSED_GROUP);
        assertThat(closed.getStatus()).isEqualTo(ProjectStatus.CLOSED);
        assertThat(closed.getProjectGroupId()).isEqualTo(CLOSED_GROUP);
        assertThat(Files.exists(workspaceFolder))
                .as("workspace folder must be gone after close")
                .isFalse();

        long snapshots = mongo.getCollection("workspace_snapshots")
                .countDocuments(Filters.eq("projectId", name));
        assertThat(snapshots)
                .as("close removes any lingering snapshots — close is terminal")
                .isZero();

        // bring on a CLOSED project must reject (claim refuses CLOSED).
        assertThatThrownBy(() -> lifecycleService.bring(TENANT, name))
                .isInstanceOf(ProjectService.ProjectClosedException.class);
    }

    @Test
    void suspend_thenSuspend_isIdempotent() {
        String name = uniqueName("idem");
        projectService.create(TENANT, name, "idempotent suspend", null, List.of());
        lifecycleService.bring(TENANT, name);
        lifecycleService.suspend(TENANT, name);

        ProjectDocument again = lifecycleService.suspend(TENANT, name);
        assertThat(again.getStatus())
                .as("suspend on already-SUSPENDED should be a no-op")
                .isEqualTo(ProjectStatus.SUSPENDED);
    }

    private Path projectFolder(String projectName) {
        return Path.of(workspaceProperties.getRoot()).toAbsolutePath()
                .normalize().resolve(projectName).normalize();
    }

    private static String uniqueName(String prefix) {
        return "wstest-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
