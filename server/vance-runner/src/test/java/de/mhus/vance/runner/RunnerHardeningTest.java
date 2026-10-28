package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What a production runner refuses: the settings a development runner relaxes are off here. */
class RunnerHardeningTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    private RunnerWorkspace workspace;
    private final Map<String, RunnerOperation> ops = new HashMap<>();

    @BeforeEach
    void setUp() {
        workspace = new RunnerWorkspace(
                tmp,
                null,
                null,
                RunnerWorkspace.DEFAULT_NPM_REGISTRY,
                RunnerWorkspace.DEFAULT_DISK_HARD_PERCENT,
                false,
                false);
        register();
    }

    @AfterEach
    void tearDown() {
        workspace.close();
    }

    private void register() {
        ops.clear();
        for (List<RunnerOperation> group : List.of(
                RootDirOperations.all(workspace),
                FileOperations.all(workspace),
                ExecOperations.all(workspace),
                ProjectOperations.all(workspace))) {
            group.forEach(op -> ops.put(op.name(), op));
        }
    }

    private static RunnerTokens.Claims claims(String project, String rootDir) {
        return new RunnerTokens.Claims(
                "acme", project, rootDir, "jti", Instant.now().plusSeconds(60));
    }

    private JsonNode call(String op, RunnerTokens.Claims claims, Map<String, Object> args) throws OperationException {
        JsonNode result = ops.get(op)
                .execute(new Call(claims, JSON.valueToTree(args), new ByteArrayInputStream(new byte[0])))
                .result();
        return result == null ? JSON.createObjectNode() : result;
    }

    private String newTempDir(RunnerTokens.Claims claims, String label) throws OperationException {
        return call("root.create", claims, Map.of("type", "temp", "creatorProcessId", "p1", "label", label))
                .get("dirName")
                .asString();
    }

    @Test
    void withoutAnExecWrapperCommandsAreRefusedUnlessTheOperatorOptsIn() throws Exception {
        var c = claims("proj", null);
        String dir = newTempDir(c, "work");

        assertThatThrownBy(() -> call("exec.start", c, Map.of("dirName", dir, "command", "echo hi")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("exec-unsandboxed"));
    }

    @Test
    void aTokenPinnedToOneRootDirSeesOnlyThatRootDirsJobs() throws Exception {
        workspace.close();
        workspace = new RunnerWorkspace(tmp.resolve("open"), null);
        register();
        var whole = claims("proj", null);
        String a = newTempDir(whole, "a");
        String b = newTempDir(whole, "b");
        String jobInB = call("exec.start", whole, Map.of("dirName", b, "command", "echo secret", "waitMs", 10_000))
                .get("id")
                .asString();
        call("exec.start", whole, Map.of("dirName", a, "command", "echo mine", "waitMs", 10_000));
        var pinnedToA = claims("proj", a);

        assertThat(call("exec.list", pinnedToA, Map.of()).get("jobs")).hasSize(1);
        for (String op : List.of("exec.status", "exec.stat", "exec.tail", "exec.kill")) {
            assertThatThrownBy(() -> call(op, pinnedToA, Map.of("id", jobInB)))
                    .as(op)
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("unknown-job"));
        }
        assertThat(call("exec.list", whole, Map.of()).get("jobs")).hasSize(2);
    }

    @Test
    void gitsOwnFilesAreNotEditableThroughTheFileOperations() throws Exception {
        var c = claims("proj", null);
        String dir = newTempDir(c, "work");

        for (String path : List.of(".git/config", "sub/.git/hooks/pre-commit", ".GIT/config")) {
            assertThatThrownBy(() -> call("file.write", c, Map.of("dirName", dir, "path", path, "content", "x")))
                    .as(path)
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("git-internals"));
            assertThatThrownBy(() ->
                            call("file.edit", c, Map.of("dirName", dir, "path", path, "oldText", "a", "newText", "b")))
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("git-internals"));
            assertThatThrownBy(() -> call("file.delete", c, Map.of("dirName", dir, "path", path)))
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("git-internals"));
        }
        call("file.write", c, Map.of("dirName", dir, "path", ".gitignore", "content", "build/"));
    }

    @Test
    void aLocalRepositoryIsNotACloneSource() throws Exception {
        var c = claims("proj", null);
        Path other = tmp.resolve("workspaces/acme/other/git-1");
        java.nio.file.Files.createDirectories(other);

        for (String url : List.of(other.toString(), "file://" + other, "ext::sh -c id")) {
            assertThatThrownBy(() -> call(
                            "root.create",
                            c,
                            Map.of("type", "git", "creatorProcessId", "p1", "metadata", Map.of("repoUrl", url))))
                    .as(url)
                    .hasMessageContaining("http(s)");
        }
    }

    @Test
    void aPythonInterpreterMustBeASystemOne() throws Exception {
        var c = claims("proj", null);

        for (String interpreter :
                List.of("/app/data/workspaces/acme/proj/work/python3", "./python3", "sh", "/usr/../tmp/python3")) {
            assertThatThrownBy(() -> call(
                            "root.create",
                            c,
                            Map.of(
                                    "type",
                                    "python",
                                    "creatorProcessId",
                                    "p1",
                                    "metadata",
                                    Map.of("pythonPath", interpreter))))
                    .as(interpreter)
                    .hasMessageContaining("system interpreter");
        }
    }

    @Test
    void aRenameToAnInvalidNameLeavesTheProjectAlone() throws Exception {
        var c = claims("proj", null);
        String dir = newTempDir(c, "work");

        assertThatThrownBy(() -> call("project.rename", c, Map.of("newProjectId", "../escape")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("bad-name"));

        assertThat(call("root.list", c, Map.of())
                        .get("rootDirs")
                        .get(0)
                        .get("dirName")
                        .asString())
                .isEqualTo(dir);
    }
}
