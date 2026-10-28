package de.mhus.vance.brain.tools.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.workspace.WorkspaceCountTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceFindTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceGrepTool;
import de.mhus.vance.brain.tools.workspace.WorkspaceHeadTailTool;
import de.mhus.vance.brain.tools.worktarget.ExecKillTool;
import de.mhus.vance.brain.tools.worktarget.ExecRunTool;
import de.mhus.vance.brain.tools.worktarget.ExecStatusTool;
import de.mhus.vance.brain.tools.worktarget.ExecTailTool;
import de.mhus.vance.brain.tools.worktarget.FileCountTool;
import de.mhus.vance.brain.tools.worktarget.FileDeleteTool;
import de.mhus.vance.brain.tools.worktarget.FileEditTool;
import de.mhus.vance.brain.tools.worktarget.FileFindTool;
import de.mhus.vance.brain.tools.worktarget.FileGrepTool;
import de.mhus.vance.brain.tools.worktarget.FileHeadTailTool;
import de.mhus.vance.brain.tools.worktarget.FileListTool;
import de.mhus.vance.brain.tools.worktarget.FileReadTool;
import de.mhus.vance.brain.tools.worktarget.FileWriteTool;
import de.mhus.vance.runner.RunnerConfig;
import de.mhus.vance.runner.RunnerIdentity;
import de.mhus.vance.runner.RunnerMain;
import de.mhus.vance.runner.RunnerServer;
import de.mhus.vance.runner.RunnerWorkspace;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.runner.RunnerBinding;
import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.runner.RunnerClient;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code runner_*} tools against a real runner over HTTP: real age envelope, real
 * brain-minted token, real RootDirs and processes on the runner side.
 */
class RunnerToolsEndToEndTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";

    @TempDir
    Path tmp;

    private RunnerWorkspace runnerWorkspace;
    private RunnerServer server;
    private TenantService tenants;
    private RunnerToolConfiguration config;
    private RunnerWork work;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair tenantKey = generator.generateKeyPair();
        KeyService keys = mock(KeyService.class);
        when(keys.getLatestPrivateKey(TENANT, KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(tenantKey.getPrivate()));

        RunnerIdentity identity = RunnerIdentity.loadOrCreate(tmp.resolve("runner.identity"));
        RunnerConfig runnerConfig = new RunnerConfig(
                "runner-acme",
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("keys.pem"),
                8L << 20);
        runnerWorkspace = new RunnerWorkspace(tmp.resolve("data"), null);
        server = new RunnerServer(
                runnerConfig,
                identity,
                List.of(tenantKey.getPublic()),
                RunnerMain.operations(runnerConfig, runnerWorkspace),
                Clock.systemUTC());
        server.start();

        tenants = mock(TenantService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(
                        ProjectDocument.builder().tenantId(TENANT).name(PROJECT).build()));
        registerRunner("http://localhost:" + server.port(), identity.recipient());
        RunnerBindingService bindings = new RunnerBindingService(tenants, projects);
        work = new RunnerWork(new RunnerClient(bindings, new JwtService(keys)), bindings);
        config = new RunnerToolConfiguration();
    }

    @AfterEach
    void tearDown() {
        server.close();
        runnerWorkspace.close();
    }

    private void registerRunner(String url, String recipient) {
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId("runner-acme")
                .url(url)
                .recipient(recipient)
                .build();
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(
                        TenantDocument.builder().name(TENANT).runner(binding).build()));
    }

    private static ToolInvocationContext ctx(String processId) {
        return new ToolInvocationContext(TENANT, PROJECT, "session-1", processId, "alice");
    }

    @Test
    void aProcessGetsItsOwnTempRootDirOnTheRunner_andKeepsIt() {
        Tool write = config.runnerFileWrite(work);
        Tool read = config.runnerFileRead(work);

        Map<String, Object> written = write.invoke(Map.of("path", "notes.txt", "content", "hello"), ctx("p1"));
        Map<String, Object> again = read.invoke(Map.of("path", "notes.txt"), ctx("p1"));
        Map<String, Object> other = read.invoke(Map.of("path", "notes.txt"), ctx("p1"));

        assertThat(again.get("content")).isEqualTo("hello");
        assertThat(again.get("dirName")).isEqualTo(written.get("dirName")).isEqualTo(other.get("dirName"));
        // Another process has its own RootDir: the file is not there.
        assertThatThrownBy(() -> read.invoke(Map.of("path", "notes.txt"), ctx("p2")))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void filesRoundTripThroughEveryTool() {
        Tool write = config.runnerFileWrite(work);
        Tool read = config.runnerFileRead(work);
        Tool edit = config.runnerFileEdit(work);
        Tool list = config.runnerFileList(work);
        Tool delete = config.runnerFileDelete(work);
        var c = ctx("p1");

        write.invoke(Map.of("path", "src/a.txt", "content", "one two"), c);
        String hash = (String) read.invoke(Map.of("path", "src/a.txt"), c).get("contentHash");
        edit.invoke(Map.of("path", "src/a.txt", "oldText", "two", "newText", "2", "expectedContentHash", hash), c);

        assertThat(read.invoke(Map.of("path", "src/a.txt"), c).get("content")).isEqualTo("one 2");
        assertThat(list.invoke(Map.of(), c).get("entries")).isEqualTo(List.of("src/"));
        assertThat(delete.invoke(Map.of("path", "src/a.txt"), c).get("deleted")).isEqualTo(true);
        assertThat(list.invoke(Map.of(), c).get("entries")).isEqualTo(List.of());
    }

    @Test
    void theSearchesRunOnTheRunner_withTheirWorkTwinsSchemas() {
        var c = ctx("p1");
        config.runnerFileWrite(work).invoke(Map.of("path", "src/A.java", "content", "one\nTODO two\nthree\n"), c);
        config.runnerFileWrite(work).invoke(Map.of("path", "notes.txt", "content", "TODO later\n"), c);

        Map<String, Object> found =
                config.runnerFileFind(work, new WorkspaceFindTool(null)).invoke(Map.of("pathGlob", "**/*.java"), c);
        Map<String, Object> grepped =
                config.runnerFileGrep(work, new WorkspaceGrepTool(null)).invoke(Map.of("pattern", "TODO"), c);
        Map<String, Object> counted =
                config.runnerFileCount(work, new WorkspaceCountTool(null)).invoke(Map.of("path", "src/A.java"), c);
        Map<String, Object> slices = config.runnerFileHeadTail(work, new WorkspaceHeadTailTool(null))
                .invoke(Map.of("path", "src/A.java", "head", 1, "tail", 1), c);

        assertThat(found.get("matchCount")).isEqualTo(1);
        assertThat(grepped.get("matchCount")).isEqualTo(2);
        assertThat(counted.get("lines")).isEqualTo(3);
        assertThat(slices.get("totalLines")).isEqualTo(3);
        assertThatThrownBy(() ->
                        config.runnerFileGrep(work, new WorkspaceGrepTool(null)).invoke(Map.of("pattern", "("), c))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Invalid regex");
    }

    @Test
    void aCommandRunsOnTheRunner_andItsOutputComesBack() {
        Tool run = config.runnerExecRun(work);
        Tool status = config.runnerExecStatus(work);
        Tool tail = config.runnerExecTail(work);
        var c = ctx("p1");
        config.runnerFileWrite(work).invoke(Map.of("path", "x.txt", "content", "1"), c);

        Map<String, Object> result = run.invoke(Map.of("command", "ls; echo done", "waitMs", 10_000), c);

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat((String) result.get("stdout")).contains("x.txt").contains("done");
        String id = (String) result.get("id");
        assertThat(status.invoke(Map.of("id", id), c).get("exitCode")).isEqualTo(0);
        assertThat(tail.invoke(Map.of("id", id, "n", 1), c).get("lines")).isEqualTo(List.of("done"));
    }

    @Test
    void aRunnerRefusal_becomesAToolErrorTheModelCanActOn() {
        Tool read = config.runnerFileRead(work);

        assertThatThrownBy(() -> read.invoke(Map.of("path", "a.txt", "dirName", "no-such-dir"), ctx("p1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown RootDir");
    }

    @Test
    void withoutARegisteredRunner_theCallSaysSo() {
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(TenantDocument.builder().name(TENANT).build()));

        assertThatThrownBy(() -> config.runnerExecRun(work).invoke(Map.of("command", "ls"), ctx("p1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No runner is registered");
        assertThat(work.available(TENANT, PROJECT)).isFalse();
    }

    @Test
    void aRunnerThatCannotBeReached_isReportedAsSuch() {
        server.close();

        assertThatThrownBy(() -> config.runnerFileList(work).invoke(Map.of("dirName", "x"), ctx("p1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not reachable");
    }

    @Test
    void aStoredKeyThatIsNotAnAgeRecipient_isReportedAsABadRegistration() {
        registerRunner("http://localhost:" + server.port(), "not-a-key");

        assertThatThrownBy(() -> config.runnerFileList(work).invoke(Map.of("dirName", "x"), ctx("p1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot be used")
                .hasMessageContaining("runner set");
    }

    @Test
    void everyRunnerToolDeclaresExactlyTheParametersOfItsWrapper() {
        // The work-target wrappers reject a parameter the active backend does not declare.
        Map<Tool, Tool> pairs = Map.ofEntries(
                Map.entry(config.runnerFileRead(work), new FileReadTool(null)),
                Map.entry(config.runnerFileWrite(work), new FileWriteTool(null)),
                Map.entry(config.runnerFileEdit(work), new FileEditTool(null)),
                Map.entry(config.runnerFileList(work), new FileListTool(null)),
                Map.entry(config.runnerFileDelete(work), new FileDeleteTool(null)),
                Map.entry(config.runnerExecRun(work), new ExecRunTool(null)),
                Map.entry(config.runnerExecStatus(work), new ExecStatusTool(null)),
                Map.entry(config.runnerExecTail(work), new ExecTailTool(null)),
                Map.entry(config.runnerExecKill(work), new ExecKillTool(null)),
                Map.entry(config.runnerFileFind(work, new WorkspaceFindTool(null)), new FileFindTool(null)),
                Map.entry(config.runnerFileGrep(work, new WorkspaceGrepTool(null)), new FileGrepTool(null)),
                Map.entry(config.runnerFileCount(work, new WorkspaceCountTool(null)), new FileCountTool(null)),
                Map.entry(
                        config.runnerFileHeadTail(work, new WorkspaceHeadTailTool(null)), new FileHeadTailTool(null)));

        pairs.forEach((runnerTool, wrapper) -> {
            assertThat(properties(runnerTool)).as(runnerTool.name()).isEqualTo(properties(wrapper));
            assertThat(runnerTool.paramsSchema().get("required"))
                    .as(runnerTool.name() + " required")
                    .isEqualTo(wrapper.paramsSchema().get("required"));
        });
    }

    private static java.util.Set<String> properties(Tool tool) {
        return ((Map<?, ?>) tool.paramsSchema().get("properties"))
                .keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }
}
