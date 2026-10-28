package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The RootDir store on a real temp directory, with handlers that record what they were asked. */
class RootDirStoreTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";

    @TempDir
    Path tmp;

    private final List<String> calls = new ArrayList<>();
    private RecordingHandler notes;
    private RootDirStore store;

    /** Handler type {@code notes}: writes a file on init/recover, can be made to fail. */
    private final class RecordingHandler implements WorkspaceContentHandler {
        boolean failInit;
        boolean failRecover;

        @Override
        public String type() {
            return "notes";
        }

        @Override
        public void init(RootDirHandle handle, RootDirSpec spec) {
            calls.add("init:" + handle.getDirName());
            if (failInit) {
                throw new IllegalStateException("init boom");
            }
            write(handle.getPath().resolve("hello.txt"), "hi");
            Map<String, Object> meta = new java.util.HashMap<>();
            meta.put("seen", "init");
            handle.getDescriptor().setMetadata(meta);
        }

        @Override
        public void suspend(RootDirHandle handle) {
            calls.add("suspend:" + handle.getDirName());
        }

        @Override
        public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
            calls.add("recover:" + handle.getDirName());
            if (failRecover) {
                throw new IllegalStateException("recover boom");
            }
            write(handle.getPath().resolve("back.txt"), "again");
        }

        @Override
        public void close(RootDirHandle handle) {
            calls.add("close:" + handle.getDirName());
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        notes = new RecordingHandler();
        store = new RootDirStore(
                () -> new RootDirStore.Settings(tmp.resolve("ws"), 0, 0), List.of(notes, new TempHandler()));
    }

    private RootDirHandle create(String label) {
        return store.createRootDir(RootDirSpec.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .type("notes")
                .creatorProcessId("proc-1")
                .labelHint(label)
                .build());
    }

    // ───────────────────────── workspace ─────────────────────────

    @Test
    void init_createsTheFolderAndWorkspaceJson_idempotently() {
        Workspace first = store.init(TENANT, PROJECT);
        Workspace again = store.init(TENANT, PROJECT);

        assertThat(first.getRoot()).isEqualTo(tmp.resolve("ws").resolve(TENANT).resolve(PROJECT));
        assertThat(first.getRoot().resolve("workspace.json")).isRegularFile();
        assertThat(again.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(store.exists(TENANT, PROJECT)).isTrue();
        assertThat(store.get(TENANT, PROJECT)).isPresent();
        assertThat(store.get(TENANT, "other")).isEmpty();
    }

    @Test
    void blankScope_isRefused() {
        assertThatThrownBy(() -> store.init(" ", PROJECT))
                .isInstanceOf(RootDirException.class)
                .hasMessage("tenantId is required");
        assertThatThrownBy(() -> store.listRootDirs(TENANT, ""))
                .isInstanceOf(RootDirException.class)
                .hasMessage("projectId is required");
    }

    @Test
    void duplicateHandlerTypes_areRefusedAtConstruction() {
        assertThatThrownBy(() -> new RootDirStore(
                        () -> new RootDirStore.Settings(tmp, 0, 0), List.of(new TempHandler(), new TempHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate WorkspaceContentHandler for type 'temp'");
    }

    @Test
    void dispose_closesEveryRootDirAndRemovesTheFolder() {
        create("a");
        create("b");

        store.dispose(TENANT, PROJECT);

        assertThat(calls).contains("close:a", "close:b");
        assertThat(store.exists(TENANT, PROJECT)).isFalse();
        store.dispose(TENANT, PROJECT); // no folder: a no-op
    }

    @Test
    void rename_movesTheFolderAndRewritesTheMetadata() {
        create("a");

        long rewritten = store.rename(TENANT, PROJECT, "kunde-y");

        assertThat(rewritten).isEqualTo(2); // workspace.json + one descriptor
        assertThat(store.exists(TENANT, PROJECT)).isFalse();
        assertThat(store.get(TENANT, "kunde-y").orElseThrow().getProjectId()).isEqualTo("kunde-y");
        RootDirHandle moved = store.getRootDir(TENANT, "kunde-y", "a").orElseThrow();
        assertThat(moved.getDescriptor().getProjectId()).isEqualTo("kunde-y");
        assertThat(moved.getPath().resolve("hello.txt")).hasContent("hi");
    }

    @Test
    void rename_ontoAnExistingWorkspace_isRefused_andWithoutFolderIsANoOp() {
        create("a");
        store.init(TENANT, "kunde-y");

        assertThatThrownBy(() -> store.rename(TENANT, PROJECT, "kunde-y"))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("already exists");
        assertThat(store.rename(TENANT, "no-folder", "whatever")).isZero();
    }

    // ───────────────────────── RootDirs ─────────────────────────

    @Test
    void createRootDir_writesTheDescriptorFirst_runsTheHandler_andKeepsItsEnrichment() throws Exception {
        RootDirHandle handle = create("build");

        assertThat(handle.getDirName()).isEqualTo("build");
        assertThat(handle.getPath().resolve("hello.txt")).hasContent("hi");
        Path descriptorFile = handle.getPath().getParent().resolve("build.json");
        assertThat(Files.readString(descriptorFile))
                .contains("\"dirName\" : \"build\"")
                .contains("\"seen\" : \"init\"");
        assertThat(store.getRootDir(TENANT, PROJECT, "build")
                        .orElseThrow()
                        .getDescriptor()
                        .getMetadata())
                .containsEntry("seen", "init");
    }

    @Test
    void createRootDir_sameLabel_getsASuffix_andABadLabelAUuid() {
        assertThat(create("build").getDirName()).isEqualTo("build");
        assertThat(create("build").getDirName()).isEqualTo("build-2");
        assertThat(create("build").getDirName()).isEqualTo("build-3");
        assertThat(create("../evil").getDirName()).matches("[0-9a-f-]{36}");
        assertThat(create("workspace").getDirName()).isEqualTo("workspace-workspace");
    }

    @Test
    void createRootDir_handlerFailure_rollsBackTheDiskState() {
        notes.failInit = true;

        assertThatThrownBy(() -> create("broken"))
                .isInstanceOf(RootDirException.class)
                .hasMessage("Handler 'notes' failed to init broken: init boom");

        Path workspace = tmp.resolve("ws").resolve(TENANT).resolve(PROJECT);
        assertThat(workspace.resolve("broken")).doesNotExist();
        assertThat(workspace.resolve("broken.json")).doesNotExist();
    }

    @Test
    void createRootDir_unknownTypeOrMissingFields_areRefused() {
        assertThatThrownBy(() -> store.createRootDir(RootDirSpec.builder()
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .type("nope")
                        .creatorProcessId("p")
                        .build()))
                .hasMessage("No handler registered for type 'nope'");
        assertThatThrownBy(() -> store.createRootDir(RootDirSpec.builder()
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .type("notes")
                        .build()))
                .hasMessage("RootDirSpec.creatorProcessId is required");
    }

    @Test
    void listRootDirs_isSortedAndSkipsOrphansAndBrokenDescriptors() throws Exception {
        create("b");
        create("a");
        Path workspace = tmp.resolve("ws").resolve(TENANT).resolve(PROJECT);
        Files.createDirectories(workspace.resolve("orphan-folder"));
        Files.writeString(
                workspace.resolve("orphan-descriptor.json"),
                "{\"tenant\":\"acme\",\"projectId\":\"kunde-x\",\"dirName\":\"orphan-descriptor\","
                        + "\"type\":\"notes\",\"deleteOnCreatorClose\":false}");
        Files.writeString(workspace.resolve("corrupt.json"), "this is not json");

        assertThat(store.listRootDirs(TENANT, PROJECT))
                .extracting(RootDirHandle::getDirName)
                .containsExactly("a", "b");
        assertThat(store.getRootDirTypes(TENANT, PROJECT)).containsExactly("notes");
        assertThat(store.listRootDirs(TENANT, "no-folder")).isEmpty();
    }

    @Test
    void brokenWorkspaceJson_isARootDirException() throws Exception {
        Path workspace =
                Files.createDirectories(tmp.resolve("ws").resolve(TENANT).resolve(PROJECT));
        Files.writeString(workspace.resolve("workspace.json"), "{ broken");

        assertThatThrownBy(() -> store.get(TENANT, PROJECT))
                .isInstanceOf(RootDirException.class)
                .hasMessageStartingWith("Cannot read workspace meta");
    }

    @Test
    void disposeRootDir_callsCloseThenRemovesFolderAndDescriptor() {
        RootDirHandle handle = create("a");

        store.disposeRootDir(TENANT, PROJECT, "a");
        store.disposeRootDir(TENANT, PROJECT, "a"); // already gone: no-op

        assertThat(calls.stream().filter("close:a"::equals).count()).isEqualTo(1);
        assertThat(handle.getPath()).doesNotExist();
        assertThat(handle.getPath().getParent().resolve("a.json")).doesNotExist();
    }

    @Test
    void disposeByCreator_removesOnlyThatCreatorsDeleteOnCloseDirs() {
        RootDirHandle mine = store.createRootDir(RootDirSpec.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .type("notes")
                .creatorProcessId("proc-1")
                .labelHint("mine")
                .deleteOnCreatorClose(true)
                .build());
        RootDirHandle keep = create("keep");
        RootDirHandle others = store.createRootDir(RootDirSpec.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .type("notes")
                .creatorProcessId("proc-2")
                .labelHint("others")
                .deleteOnCreatorClose(true)
                .build());

        store.disposeByCreator(TENANT, PROJECT, "proc-1");

        assertThat(mine.getPath()).doesNotExist();
        assertThat(keep.getPath()).exists();
        assertThat(others.getPath()).exists();
    }

    @Test
    void descriptorFormat_ofExistingWorkspacesStaysReadable() throws Exception {
        // A descriptor as older versions wrote it — the format is the compatibility contract.
        Path workspace =
                Files.createDirectories(tmp.resolve("ws").resolve(TENANT).resolve(PROJECT));
        Files.writeString(workspace.resolve("workspace.json"), """
                {
                  "tenant" : "acme",
                  "projectId" : "kunde-x",
                  "createdAt" : "2026-10-01T10:00:00Z"
                }
                """);
        Files.createDirectories(workspace.resolve("old"));
        Files.writeString(workspace.resolve("old.json"), """
                {
                  "tenant" : "acme",
                  "projectId" : "kunde-x",
                  "dirName" : "old",
                  "label" : "legacy",
                  "type" : "notes",
                  "creatorProcessId" : "proc-9",
                  "createdAt" : "2026-10-01T10:00:01Z",
                  "deleteOnCreatorClose" : true,
                  "metadata" : { "repoUrl" : "https://example.test/x.git" }
                }
                """);

        Workspace ws = store.get(TENANT, PROJECT).orElseThrow();
        RootDirHandle handle = store.getRootDir(TENANT, PROJECT, "old").orElseThrow();

        assertThat(ws.getCreatedAt()).isEqualTo("2026-10-01T10:00:00Z");
        assertThat(handle.getDescriptor().getLabel()).isEqualTo("legacy");
        assertThat(handle.deleteOnCreatorClose()).isTrue();
        assertThat(handle.creatorProcessId()).isEqualTo("proc-9");
        assertThat(handle.getDescriptor().getMetadata()).containsEntry("repoUrl", "https://example.test/x.git");
    }

    // ───────────────────────── working dir and temp ─────────────────────────

    @Test
    void workingDir_needsAnExistingRootDir_andIsForgottenWhenItGoes() {
        create("a");

        assertThatThrownBy(() -> store.setWorkingDir(TENANT, PROJECT, "proc-1", "nope"))
                .hasMessageContaining("non-existent");
        store.setWorkingDir(TENANT, PROJECT, "proc-1", "a");
        assertThat(store.getWorkingDir(TENANT, PROJECT, "proc-1")).contains("a");

        store.disposeRootDir(TENANT, PROJECT, "a");
        assertThat(store.getWorkingDir(TENANT, PROJECT, "proc-1")).isEmpty();
        assertThat(store.getWorkingDir(TENANT, PROJECT, " ")).isEmpty();
    }

    @Test
    void tempRootDir_isCreatedOncePerCreator_andGoesWithTheCreator() {
        RootDirHandle first = store.getOrCreateTempRootDir(TENANT, PROJECT, "proc-1");
        RootDirHandle again = store.getOrCreateTempRootDir(TENANT, PROJECT, "proc-1");
        Path file = store.createTempFile(TENANT, PROJECT, "proc-1", "x-", ".bin");
        Path dir = store.createTempDirectory(TENANT, PROJECT, "proc-1", "d-");

        assertThat(again.getDirName()).isEqualTo(first.getDirName());
        assertThat(file.getParent()).isEqualTo(first.getPath());
        assertThat(file.getFileName().toString()).startsWith("x-").endsWith(".bin");
        assertThat(dir.getParent()).isEqualTo(first.getPath());

        store.disposeByCreator(TENANT, PROJECT, "proc-1");
        assertThat(first.getPath()).doesNotExist();
        // A later call creates a fresh one.
        assertThat(store.getOrCreateTempRootDir(TENANT, PROJECT, "proc-1").getPath())
                .exists();
    }

    @Test
    void withLabelLock_runsTheActionAndReturnsItsResult() {
        assertThat(store.withLabelLock(TENANT, PROJECT, "ws", () -> "done")).isEqualTo("done");
    }

    // ───────────────────────── suspend / recover ─────────────────────────

    @Test
    void suspendAll_persistsBeforeDeleting_thenRecoverRebuildsTheWorkspace() {
        create("a");
        create("b");
        List<RootDirStore.Snapshot> kept = new ArrayList<>();
        List<Boolean> contentStillThere = new ArrayList<>();

        store.suspendAll(TENANT, PROJECT, handle -> {
            contentStillThere.add(Files.exists(handle.getPath().resolve("hello.txt")));
            kept.add(new RootDirStore.Snapshot(handle.getDirName(), handle.getDescriptor()));
        });

        assertThat(contentStillThere).containsExactly(true, true);
        assertThat(calls).contains("suspend:a", "suspend:b");
        assertThat(store.exists(TENANT, PROJECT)).isFalse();

        Workspace ws = store.recover(TENANT, PROJECT, kept);

        assertThat(ws.getProjectId()).isEqualTo(PROJECT);
        assertThat(calls).contains("recover:a", "recover:b");
        assertThat(store.listRootDirs(TENANT, PROJECT))
                .extracting(RootDirHandle::getDirName)
                .containsExactly("a", "b");
        assertThat(store.getRootDir(TENANT, PROJECT, "a")
                        .orElseThrow()
                        .getPath()
                        .resolve("back.txt"))
                .hasContent("again");
    }

    @Test
    void suspendAll_withoutFolder_isANoOp_andWithoutAHandlerFails() {
        store.suspendAll(TENANT, "no-folder", handle -> {
            throw new AssertionError("nothing to persist");
        });

        RootDirStore bare =
                new RootDirStore(() -> new RootDirStore.Settings(tmp.resolve("ws"), 0, 0), List.of(new TempHandler()));
        create("a");
        assertThatThrownBy(() -> bare.suspendAll(TENANT, PROJECT, h -> {}))
                .isInstanceOf(RootDirException.class)
                .hasMessageContaining("No handler registered for type 'notes' on suspend of a");
    }

    @Test
    void recover_handlerFailure_rollsBackThatRootDir_andAbortsKeepingTheSnapshotsToTheCaller() {
        create("a");
        List<RootDirStore.Snapshot> kept = new ArrayList<>();
        store.suspendAll(
                TENANT,
                PROJECT,
                handle -> kept.add(new RootDirStore.Snapshot(handle.getDirName(), handle.getDescriptor())));
        notes.failRecover = true;

        assertThatThrownBy(() -> store.recover(TENANT, PROJECT, kept))
                .isInstanceOf(RootDirException.class)
                .hasMessage("Handler 'notes' failed to recover a: recover boom");

        Path workspace = tmp.resolve("ws").resolve(TENANT).resolve(PROJECT);
        assertThat(workspace.resolve("a")).doesNotExist();
        assertThat(workspace.resolve("a.json")).doesNotExist();
    }

    @Test
    void recover_skipsSnapshotsOfUnknownTypes_andClearsAPartialFolderFirst() throws Exception {
        Path stale = Files.createDirectories(
                tmp.resolve("ws").resolve(TENANT).resolve(PROJECT).resolve("stale"));
        WorkspaceDescriptor alien = WorkspaceDescriptor.builder()
                .tenant(TENANT)
                .projectId(PROJECT)
                .dirName("alien")
                .type("unknown-type")
                .creatorProcessId("p")
                .build();

        store.recover(TENANT, PROJECT, List.of(new RootDirStore.Snapshot("alien", alien)));

        assertThat(stale).doesNotExist();
        assertThat(store.listRootDirs(TENANT, PROJECT)).isEmpty();
    }
}
