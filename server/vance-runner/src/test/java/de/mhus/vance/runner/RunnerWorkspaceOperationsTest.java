package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The runner's RootDir, file and exec operations, called directly with token claims. */
class RunnerWorkspaceOperationsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    private RunnerWorkspace workspace;
    private Map<String, RunnerOperation> ops;

    @BeforeEach
    void setUp() {
        workspace = new RunnerWorkspace(tmp, null);
        ops = new java.util.HashMap<>();
        for (List<RunnerOperation> group : List.of(
                RootDirOperations.all(workspace), FileOperations.all(workspace), ExecOperations.all(workspace))) {
            group.forEach(op -> ops.put(op.name(), op));
        }
    }

    @AfterEach
    void tearDown() {
        workspace.close();
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

    private String newTempDir(RunnerTokens.Claims claims) throws OperationException {
        return call("root.create", claims, Map.of("type", "temp", "creatorProcessId", "p1", "label", "work"))
                .get("dirName")
                .asString();
    }

    @Test
    void aRootDirCanBeCreatedListedAndDeleted() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        assertThat(call("root.list", c, Map.of())
                        .get("rootDirs")
                        .get(0)
                        .get("dirName")
                        .asString())
                .isEqualTo(dir);

        call("root.delete", c, Map.of("dirName", dir));
        assertThat(call("root.list", c, Map.of()).get("rootDirs")).isEmpty();
    }

    @Test
    void anUnprovidedRootDirType_isRefusedWithTheTypesThatExist() {
        assertThatThrownBy(() ->
                        call("root.create", claims("kunde-x", null), Map.of("type", "git", "creatorProcessId", "p1")))
                .isInstanceOfSatisfying(OperationException.class, e -> {
                    assertThat(e.code()).isEqualTo("unsupported-type");
                    assertThat(e.getMessage()).contains("ephemeral").contains("temp");
                });
    }

    @Test
    void aFileRoundTrips_withAContentHashThatGuardsEdits() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        JsonNode written = call("file.write", c, Map.of("dirName", dir, "path", "a/b.txt", "content", "hello world"));
        JsonNode read = call("file.read", c, Map.of("dirName", dir, "path", "a/b.txt"));
        assertThat(read.get("content").asString()).isEqualTo("hello world");
        assertThat(read.get("contentHash").asString())
                .isEqualTo(written.get("contentHash").asString());

        call(
                "file.edit",
                c,
                Map.of(
                        "dirName",
                        dir,
                        "path",
                        "a/b.txt",
                        "oldText",
                        "world",
                        "newText",
                        "runner",
                        "expectedContentHash",
                        read.get("contentHash").asString()));
        assertThat(call("file.read", c, Map.of("dirName", dir, "path", "a/b.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("hello runner");

        // The hash of the old content is now stale.
        assertThatThrownBy(() -> call(
                        "file.edit",
                        c,
                        Map.of(
                                "dirName",
                                dir,
                                "path",
                                "a/b.txt",
                                "oldText",
                                "runner",
                                "newText",
                                "x",
                                "expectedContentHash",
                                read.get("contentHash").asString())))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("stale-content"));
    }

    @Test
    void anEditNeedsAUniqueMatch() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "f.txt", "content", "a a"));

        assertThatThrownBy(() ->
                        call("file.edit", c, Map.of("dirName", dir, "path", "f.txt", "oldText", "a", "newText", "b")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("ambiguous"));
        assertThatThrownBy(() ->
                        call("file.edit", c, Map.of("dirName", dir, "path", "f.txt", "oldText", "zz", "newText", "b")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("not-found"));
    }

    @Test
    void listAndDelete() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "one.txt", "content", "1"));

        assertThat(call("file.list", c, Map.of("dirName", dir))
                        .get("entries")
                        .get(0)
                        .asString())
                .isEqualTo("one.txt");
        assertThat(call("file.delete", c, Map.of("dirName", dir, "path", "one.txt"))
                        .get("deleted")
                        .asBoolean())
                .isTrue();
        assertThat(call("file.list", c, Map.of("dirName", dir)).get("entries")).isEmpty();
    }

    @Test
    void aPathCannotLeaveItsRootDir() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        assertThatThrownBy(() -> call("file.read", c, Map.of("dirName", dir, "path", "../../../etc/passwd")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("escapes"));
    }

    @Test
    void aProjectNeverSeesAnotherProjectsRootDirs() throws Exception {
        String dir = newTempDir(claims("kunde-x", null));
        var other = claims("kunde-y", null);

        assertThat(call("root.list", other, Map.of()).get("rootDirs")).isEmpty();
        assertThatThrownBy(() -> call("file.read", other, Map.of("dirName", dir, "path", "a.txt")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("unknown-rootdir"));
    }

    @Test
    void aTokenPinnedToARootDir_reachesOnlyThatOne() throws Exception {
        var open = claims("kunde-x", null);
        String first = newTempDir(open);
        String second = newTempDir(open);
        var pinned = claims("kunde-x", first);

        // Without dirName the pin applies; naming the other is refused, not overridden.
        call("file.write", pinned, Map.of("path", "p.txt", "content", "pinned"));
        assertThat(call("file.read", open, Map.of("dirName", first, "path", "p.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("pinned");
        assertThatThrownBy(() -> call("file.read", pinned, Map.of("dirName", second, "path", "p.txt")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("root-not-allowed"));
        assertThat(call("root.list", pinned, Map.of()).get("rootDirs")).hasSize(1);
        assertThatThrownBy(() -> call("root.create", pinned, Map.of("type", "temp", "creatorProcessId", "p1")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("root-not-allowed"));
    }

    @Test
    void aCommandRunsInItsRootDir_andItsOutputCanBeTailed() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "marker.txt", "content", "x"));

        JsonNode started = call("exec.start", c, Map.of("dirName", dir, "command", "ls; echo done", "waitMs", 10_000));
        String id = started.get("id").asString();
        assertThat(started.get("status").asString()).isEqualTo("COMPLETED");

        List<String> lines = call("exec.tail", c, Map.of("id", id))
                .get("lines")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
        assertThat(lines).contains("marker.txt", "done");
        assertThat(call("exec.status", c, Map.of("id", id)).get("exitCode").asInt())
                .isZero();
        assertThat(call("exec.list", c, Map.of()).get("jobs")).hasSize(1);
    }

    @Test
    void theChildEnvironmentCarriesNoRunnerSecrets_andAScopedHome() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        JsonNode started = call("exec.start", c, Map.of("dirName", dir, "command", "env", "waitMs", 10_000));
        List<String> env = call("exec.tail", c, Map.of("id", started.get("id").asString(), "n", 500))
                .get("lines")
                .valueStream()
                .map(JsonNode::asString)
                .toList();

        assertThat(env).anyMatch(line -> line.startsWith("HOME=") && line.endsWith("/homes/acme/kunde-x"));
        assertThat(env).noneMatch(line -> line.startsWith("VANCE_RUNNER_"));
    }

    @Test
    void aLongJobCanBeKilled_andIsInvisibleToAnotherProject() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        String id = call("exec.start", c, Map.of("dirName", dir, "command", "sleep 60"))
                .get("id")
                .asString();

        assertThatThrownBy(() -> call("exec.kill", claims("kunde-y", null), Map.of("id", id)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("unknown-job"));
        // The worker thread attaches the process a moment after the start; a kill before that is a no-op.
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        boolean killed = false;
        while (!killed && System.nanoTime() < until) {
            killed = call("exec.kill", c, Map.of("id", id)).get("killed").asBoolean();
            if (!killed) {
                Thread.sleep(50);
            }
        }
        assertThat(killed).isTrue();
        assertThat(call("exec.status", c, Map.of("id", id)).get("status").asString())
                .isEqualTo("KILLED");
    }

    @Test
    void anExecWrapperTemplateIsApplied() throws Exception {
        workspace.close();
        workspace = new RunnerWorkspace(tmp.resolve("wrapped"), "/bin/sh -c {cmd}");
        ops.clear();
        for (List<RunnerOperation> group : List.of(
                RootDirOperations.all(workspace), FileOperations.all(workspace), ExecOperations.all(workspace))) {
            group.forEach(op -> ops.put(op.name(), op));
        }
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        JsonNode started = call("exec.start", c, Map.of("dirName", dir, "command", "echo wrapped", "waitMs", 10_000));
        assertThat(call("exec.tail", c, Map.of("id", started.get("id").asString()))
                        .get("lines")
                        .get(0)
                        .asString())
                .isEqualTo("wrapped");
    }

    @Test
    void aListingIsOneLevelDeep_andCanStartBelowASubPath() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "top.txt", "content", "1"));
        call("file.write", c, Map.of("dirName", dir, "path", "src/a.txt", "content", "2"));
        call("file.write", c, Map.of("dirName", dir, "path", "src/deep/b.txt", "content", "3"));

        assertThat(call("file.list", c, Map.of("dirName", dir))
                        .get("entries")
                        .valueStream()
                        .map(JsonNode::asString))
                .containsExactly("src/", "top.txt");
        JsonNode below = call("file.list", c, Map.of("dirName", dir, "path", "src"));
        assertThat(below.get("entries").valueStream().map(JsonNode::asString)).containsExactly("a.txt", "deep/");
        assertThat(below.get("count").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> call("file.list", c, Map.of("dirName", dir, "path", "missing")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("not-found"));
    }

    @Test
    void aWriteWithAStaleHashIsRefused_andLeavesTheFileAlone() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        String hash = call("file.write", c, Map.of("dirName", dir, "path", "f.txt", "content", "v1"))
                .get("contentHash")
                .asString();

        call("file.write", c, Map.of("dirName", dir, "path", "f.txt", "content", "v2", "expectedContentHash", hash));
        assertThatThrownBy(() -> call(
                        "file.write",
                        c,
                        Map.of("dirName", dir, "path", "f.txt", "content", "v3", "expectedContentHash", hash)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("stale-content"));
        assertThat(call("file.read", c, Map.of("dirName", dir, "path", "f.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("v2");
        // A file that is gone counts as stale too.
        assertThatThrownBy(() -> call(
                        "file.write",
                        c,
                        Map.of("dirName", dir, "path", "gone.txt", "content", "x", "expectedContentHash", hash)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("stale-content"));
    }

    @Test
    void aFinishedJobCarriesItsOutputInline_cutAtTheEnd() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        JsonNode run = call(
                "exec.start",
                c,
                Map.of(
                        "dirName",
                        dir,
                        "waitMs",
                        10_000,
                        "inlineChars",
                        100,
                        "command",
                        "i=0; while [ $i -lt 200 ]; do echo line$i; i=$((i+1)); done"));

        assertThat(run.get("stdout").asString())
                .contains("line0")
                .contains("line199")
                .contains("truncated");
        assertThat(run.get("truncated").asBoolean()).isTrue();
        assertThat(run.has("stdoutPath")).isFalse();
    }
}
