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
                RootDirOperations.all(workspace),
                FileOperations.all(workspace),
                ExecOperations.all(workspace),
                ProjectOperations.all(workspace))) {
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
                        call("root.create", claims("kunde-x", null), Map.of("type", "svn", "creatorProcessId", "p1")))
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
                RootDirOperations.all(workspace),
                FileOperations.all(workspace),
                ExecOperations.all(workspace),
                ProjectOperations.all(workspace))) {
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
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("No such path"));
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

    private static java.util.List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void findNarrowsByGlobAndSkipsGeneratedDirectories() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "src/A.java", "content", "class A {}"));
        call("file.write", c, Map.of("dirName", dir, "path", "src/b.txt", "content", "b"));
        call("file.write", c, Map.of("dirName", dir, "path", "node_modules/x/C.java", "content", "c"));

        JsonNode found = call("file.find", c, Map.of("dirName", dir, "pathGlob", "**/*.java"));

        assertThat(found.get("entries").valueStream().map(e -> e.get("path").asString()))
                .containsExactly("src/A.java");
        assertThat(found.get("generatedFilesSkipped").asInt()).isEqualTo(1);
        JsonNode all = call("file.find", c, Map.of("dirName", dir, "pathGlob", "**/*.java", "includeGenerated", true));
        assertThat(all.get("matchCount").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> call("file.find", c, Map.of("dirName", dir, "path", "nope")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("No such path"));
    }

    @Test
    void grepReturnsNumberedLinesWithContext_andRefusesABadOrSlowRegex() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "f.txt", "content", "one\nTODO two\nthree"));

        JsonNode grep = call(
                "file.grep",
                c,
                Map.of(
                        "dirName",
                        dir,
                        "pattern",
                        "todo",
                        "caseInsensitive",
                        true,
                        "contextBefore",
                        1,
                        "contextAfter",
                        1));

        JsonNode match = grep.get("matches").get(0);
        assertThat(match.get("lineNumber").asInt()).isEqualTo(2);
        assertThat(match.get("line").asString()).isEqualTo("TODO two");
        assertThat(match.get("context")).hasSize(2);
        assertThatThrownBy(() -> call("file.grep", c, Map.of("dirName", dir, "pattern", "(")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("Invalid regex"));
        assertThatThrownBy(() -> call("file.grep", c, Map.of("dirName", dir)))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("'pattern' is required"));
    }

    @Test
    void countCountsOneFileOrASubtree_andOnlyMatchingLinesWithAPattern() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "a.txt", "content", "x\nTODO\ny\n"));
        call("file.write", c, Map.of("dirName", dir, "path", "d/b.txt", "content", "TODO\nTODO\n"));

        JsonNode one = call("file.count", c, Map.of("dirName", dir, "path", "a.txt"));
        assertThat(one.get("lines").asInt()).isEqualTo(3);
        JsonNode tree = call("file.count", c, Map.of("dirName", dir));
        assertThat(tree.get("filesCounted").asInt()).isEqualTo(2);
        assertThat(tree.get("lines").asInt()).isEqualTo(5);
        JsonNode todos = call("file.count", c, Map.of("dirName", dir, "pattern", "TODO"));
        assertThat(todos.get("lines").asInt()).isEqualTo(3);
        assertThat(todos.get("totalLinesScanned").asInt()).isEqualTo(5);
        assertThatThrownBy(() -> call("file.count", c, Map.of("dirName", dir, "path", "missing")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("No such path"));
    }

    @Test
    void headTailGivesNumberedSlices() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "f.txt", "content", "1\n2\n3\n4\n5\n"));

        JsonNode slices = call("file.head_tail", c, Map.of("dirName", dir, "path", "f.txt", "head", 2, "tail", 2));

        assertThat(slices.get("totalLines").asInt()).isEqualTo(5);
        assertThat(slices.get("head").valueStream().map(r -> r.get("line").asString()))
                .containsExactly("1", "2");
        assertThat(slices.get("tail").get(0).get("lineNumber").asInt()).isEqualTo(4);
        assertThatThrownBy(() -> call("file.head_tail", c, Map.of("dirName", dir, "path", "f.txt")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("At least one"));
        assertThatThrownBy(() -> call("file.head_tail", c, Map.of("dirName", dir, "path", "nope.txt", "head", 1)))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("Not found"));
    }

    @Test
    void theWrapperTemplateGetsTheProjectsHome() throws Exception {
        workspace.close();
        workspace = new RunnerWorkspace(tmp.resolve("homed"), "/usr/bin/env HOMEARG={home} /bin/sh -c {cmd}");
        ops.clear();
        for (List<RunnerOperation> group : List.of(
                RootDirOperations.all(workspace),
                FileOperations.all(workspace),
                ExecOperations.all(workspace),
                ProjectOperations.all(workspace))) {
            group.forEach(op -> ops.put(op.name(), op));
        }
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        JsonNode started = call("exec.start", c, Map.of("dirName", dir, "command", "echo $HOMEARG", "waitMs", 10_000));

        assertThat(call("exec.tail", c, Map.of("id", started.get("id").asString()))
                        .get("lines")
                        .get(0)
                        .asString())
                .endsWith("/homes/acme/kunde-x");
    }

    private RunnerOperation.OperationResult callWithBody(
            String op, RunnerTokens.Claims claims, Map<String, Object> args, byte[] body) throws OperationException {
        return ops.get(op).execute(new Call(claims, JSON.valueToTree(args), new ByteArrayInputStream(body)));
    }

    @Test
    void aFileTravelsAsTheBody_beyondTheHeaderCap_inBothDirections() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        String big = ("0123456789abcdef".repeat(64) + "\n").repeat(3 * 1024); // 3 MiB, over the 1 MiB header cap

        var written = callWithBody(
                "file.write",
                c,
                Map.of("dirName", dir, "path", "big.txt", "contentInBody", true),
                big.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(written.result().get("chars").asInt()).isEqualTo(big.length());

        var read = callWithBody(
                "file.read",
                c,
                Map.of("dirName", dir, "path", "big.txt", "contentInBody", true, "maxChars", 10_000_000),
                new byte[0]);
        // The answer's header carries no content; the body does.
        assertThat(read.result().has("content")).isFalse();
        assertThat(read.result().get("contentHash").asString())
                .isEqualTo(written.result().get("contentHash").asString());
        assertThat(new String(read.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(big);
    }

    @Test
    void anEmptyBodyWritesAnEmptyFile_andNoContentAtAllIsRefused() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);

        callWithBody("file.write", c, Map.of("dirName", dir, "path", "empty.txt", "contentInBody", true), new byte[0]);
        assertThat(call("file.read", c, Map.of("dirName", dir, "path", "empty.txt"))
                        .get("content")
                        .asString())
                .isEmpty();
        assertThatThrownBy(() -> call("file.write", c, Map.of("dirName", dir, "path", "none.txt")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("'content' is required"));
    }

    // ───────────────────────── typed RootDirs ─────────────────────────

    private static void assumeProgram(String program) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.util.stream.Stream.of(System.getenv("PATH").split(java.io.File.pathSeparator))
                        .anyMatch(dir -> java.nio.file.Files.isExecutable(Path.of(dir, program))),
                program + " is not on this machine's PATH");
    }

    private Path sourceRepo() throws Exception {
        Path repo = java.nio.file.Files.createDirectories(tmp.resolve("source"));
        git(repo, "init", "--initial-branch=main");
        java.nio.file.Files.writeString(repo.resolve("hello.txt"), "from the repo\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void aGitRootDirIsClonedFromAnyRepository_andRecordsItsCommit() throws Exception {
        assumeProgram("git");
        var c = claims("kunde-x", null);
        Path repo = sourceRepo();

        JsonNode created = call(
                "root.create",
                c,
                Map.of(
                        "type",
                        "git",
                        "creatorProcessId",
                        "p1",
                        "label",
                        "src",
                        "metadata",
                        Map.of("repoUrl", repo.toString(), "branch", "main")));

        String dir = created.get("dirName").asString();
        assertThat(created.get("type").asString()).isEqualTo("git");
        assertThat(created.get("metadata").get("commit").asString()).hasSize(40);
        assertThat(call("file.read", c, Map.of("dirName", dir, "path", "hello.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("from the repo\n");
        // A command in that RootDir sees the repository.
        JsonNode started =
                call("exec.start", c, Map.of("dirName", dir, "command", "git log --oneline | wc -l", "waitMs", 10_000));
        assertThat(call("exec.tail", c, Map.of("id", started.get("id").asString()))
                        .get("lines")
                        .get(0)
                        .asString()
                        .strip())
                .isEqualTo("1");
    }

    @Test
    void aGitRootDirWithoutARepoUrlOrWithABrokenOne_isRefusedWithTheCause() throws Exception {
        assumeProgram("git");
        var c = claims("kunde-x", null);

        assertThatThrownBy(() -> call("root.create", c, Map.of("type", "git", "creatorProcessId", "p1")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("repoUrl"));
        assertThatThrownBy(() -> call(
                        "root.create",
                        c,
                        Map.of(
                                "type",
                                "git",
                                "creatorProcessId",
                                "p1",
                                "metadata",
                                Map.of("repoUrl", tmp.resolve("nope").toString()))))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("git clone failed"));
        // Nothing half-made is left behind.
        assertThat(call("root.list", c, Map.of()).get("rootDirs")).isEmpty();
    }

    @Test
    void aGitCredentialMustBeCompleteAndNeverComesBackInTheAnswer() throws Exception {
        assumeProgram("git");
        var c = claims("kunde-x", null);
        Path repo = sourceRepo();

        assertThatThrownBy(() -> call(
                        "root.create",
                        c,
                        Map.of(
                                "type",
                                "git",
                                "creatorProcessId",
                                "p1",
                                "metadata",
                                Map.of("repoUrl", repo.toString()),
                                "gitCredential",
                                Map.of("username", "bot"))))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("bad-args"));

        JsonNode created = call(
                "root.create",
                c,
                Map.of(
                        "type",
                        "git",
                        "creatorProcessId",
                        "p1",
                        "metadata",
                        Map.of("repoUrl", repo.toString()),
                        "gitCredential",
                        Map.of("username", "bot", "token", "s3cret-token")));
        assertThat(created.toString()).doesNotContain("s3cret-token");
        // And the RootDir's descriptor on disk does not carry it either.
        try (var files = java.nio.file.Files.walk(tmp.resolve("workspaces"))) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".json"))
                    .toList()) {
                assertThat(java.nio.file.Files.readString(file)).doesNotContain("s3cret-token");
            }
        }
    }

    @Test
    void aPythonRootDirGetsAVenv_andRunsScriptsInIt() throws Exception {
        assumeProgram("python3");
        var c = claims("kunde-x", null);

        JsonNode created =
                call("root.create", c, Map.of("type", "python", "creatorProcessId", "p1", "label", "_python"));

        String dir = created.get("dirName").asString();
        assertThat(created.get("metadata").get("pythonPath").asString()).isEqualTo("python3");
        assertThat(strings(call("file.list", c, Map.of("dirName", dir, "path", ".venv/bin"))
                        .get("entries")))
                .anyMatch(name -> name.startsWith("python"));
        call("file.write", c, Map.of("dirName", dir, "path", "hello.py", "content", "print('hi from venv')\n"));
        JsonNode started =
                call("exec.start", c, Map.of("dirName", dir, "command", ".venv/bin/python hello.py", "waitMs", 20_000));
        assertThat(started.get("stdout").asString()).contains("hi from venv");
    }

    @Test
    void aNodeRootDirIsCreated() throws Exception {
        assumeProgram("npm");
        var c = claims("kunde-x", null);

        JsonNode created =
                call("root.create", c, Map.of("type", "node", "creatorProcessId", "p1", "label", "_jsengine"));

        assertThat(created.get("type").asString()).isEqualTo("node");
        assertThat(strings(call(
                                "file.list",
                                c,
                                Map.of("dirName", created.get("dirName").asString()))
                        .get("entries")))
                .contains("package.json");
    }

    // ───────────────────────── project maintenance ─────────────────────────

    private String waitForJobStart(RunnerTokens.Claims c, String dir, String command) throws Exception {
        return call("exec.start", c, Map.of("dirName", dir, "command", command))
                .get("id")
                .asString();
    }

    @Test
    void projectInfoCountsRootDirsAndRunningJobs() throws Exception {
        var c = claims("proj-info", null);
        String dir = newTempDir(c);
        newTempDir(c);
        waitForJobStart(c, dir, "sleep 30");

        JsonNode info = call("project.info", c, Map.of());

        assertThat(info.get("rootDirs").asInt()).isEqualTo(2);
        assertThat(info.get("runningJobs").asInt()).isEqualTo(1);
        assertThat(call("project.info", claims("proj-empty", null), Map.of())
                        .get("rootDirs")
                        .asInt())
                .isZero();
        call("project.delete", c, Map.of());
    }

    @Test
    void projectDeleteRemovesEverythingTheRunnerHolds_killsJobs_andIsRepeatable() throws Exception {
        var c = claims("proj-delete", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "a.txt", "content", "x"));
        String jobId = waitForJobStart(c, dir, "sleep 60");
        call("exec.start", c, Map.of("dirName", dir, "command", "echo hi", "waitMs", 10_000));
        Path home = tmp.resolve("homes/acme/proj-delete");
        assertThat(home).isDirectory();
        var other = claims("proj-keep", null);
        String keptDir = newTempDir(other);

        JsonNode deleted = call("project.delete", c, Map.of());

        assertThat(deleted.get("rootDirs").asInt()).isEqualTo(1);
        assertThat(deleted.get("killedJobs").asInt()).isEqualTo(1);
        assertThat(deleted.get("jobs").asInt()).isEqualTo(2);
        assertThat(call("root.list", c, Map.of()).get("rootDirs")).isEmpty();
        assertThat(home).doesNotExist();
        assertThatThrownBy(() -> call("exec.status", c, Map.of("id", jobId)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("unknown-job"));
        // Another project is untouched, and a second delete is a no-op, not an error.
        assertThat(call("root.list", other, Map.of())
                        .get("rootDirs")
                        .get(0)
                        .get("dirName")
                        .asString())
                .isEqualTo(keptDir);
        assertThat(call("project.delete", c, Map.of()).get("rootDirs").asInt()).isZero();
    }

    @Test
    void projectRenameMovesRootDirsAndHome_butNotUnderRunningJobsOrOntoDataOfAnotherProject() throws Exception {
        var c = claims("proj-old", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "keep.txt", "content", "kept"));
        call(
                "exec.start",
                c,
                Map.of("dirName", dir, "command", "echo hi", "waitMs", 10_000)); // creates the project's HOME
        String running = waitForJobStart(c, dir, "sleep 60");

        assertThatThrownBy(() -> call("project.rename", c, Map.of("newProjectId", "proj-new")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("jobs-running"));
        // The worker attaches the process a moment after the start; a kill before that does nothing.
        long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (call("project.info", c, Map.of()).get("runningJobs").asInt() > 0 && System.nanoTime() < until) {
            call("exec.kill", c, Map.of("id", running));
            Thread.sleep(50);
        }

        var taken = claims("proj-taken", null);
        newTempDir(taken);
        assertThatThrownBy(() -> call("project.rename", c, Map.of("newProjectId", "proj-taken")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("name-taken"));

        call("project.rename", c, Map.of("newProjectId", "proj-new"));

        var renamed = claims("proj-new", null);
        assertThat(call("file.read", renamed, Map.of("dirName", dir, "path", "keep.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("kept");
        assertThat(call("root.list", c, Map.of()).get("rootDirs")).isEmpty();
        assertThat(tmp.resolve("homes/acme/proj-new")).isDirectory();
        assertThat(tmp.resolve("homes/acme/proj-old")).doesNotExist();
        call("project.delete", renamed, Map.of());
        call("project.delete", taken, Map.of());
    }

    @Test
    void projectOperationsRefuseATokenPinnedToOneRootDir() throws Exception {
        var open = claims("proj-pinned", null);
        String dir = newTempDir(open);
        var pinned = claims("proj-pinned", dir);

        for (String op : List.of("project.info", "project.delete", "project.rename")) {
            assertThatThrownBy(() -> call(op, pinned, Map.of("newProjectId", "x")))
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("root-not-allowed"));
        }
        call("project.delete", open, Map.of());
    }

    @Test
    void aFullVolumeRefusesWritesAndCommands_butStillAnswersReadsAndKills() throws Exception {
        var c = claims("kunde-x", null);
        String dir = newTempDir(c);
        call("file.write", c, Map.of("dirName", dir, "path", "keep.txt", "content", "x"));
        workspace.close();
        // A limit of 1 % is exceeded by any disk that holds anything.
        workspace = new RunnerWorkspace(tmp, null, null, RunnerWorkspace.DEFAULT_NPM_REGISTRY, 1);
        ops.clear();
        for (List<RunnerOperation> group : List.of(
                RootDirOperations.all(workspace),
                FileOperations.all(workspace),
                ExecOperations.all(workspace),
                ProjectOperations.all(workspace))) {
            group.forEach(op -> ops.put(op.name(), op));
        }

        assertThatThrownBy(() -> call("file.write", c, Map.of("dirName", dir, "path", "more.txt", "content", "y")))
                .isInstanceOfSatisfying(OperationException.class, e -> {
                    assertThat(e.code()).isEqualTo("disk-full");
                    assertThat(e.getMessage()).contains("% full").contains("more.txt");
                });
        assertThatThrownBy(() -> call("exec.start", c, Map.of("dirName", dir, "command", "echo hi")))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("disk-full"));
        // What frees space or only looks stays possible.
        assertThat(call("file.read", c, Map.of("dirName", dir, "path", "keep.txt"))
                        .get("content")
                        .asString())
                .isEqualTo("x");
        assertThat(call("file.delete", c, Map.of("dirName", dir, "path", "keep.txt"))
                        .get("deleted")
                        .asBoolean())
                .isTrue();
        call("root.delete", c, Map.of("dirName", dir));
    }
}
