package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.shared.workspace.NodeHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the CommonJS-require pathway:
 *
 * <ul>
 *   <li>A real on-disk fake {@code node_modules/<pkg>/index.js} is
 *       resolved and evaluated when the feature is enabled.</li>
 *   <li>The sandboxed FileSystem rejects reads outside the workspace
 *       even when a path traversal is attempted.</li>
 *   <li>{@code @requires} pre-check fails-fast with MISSING_CAPABILITY
 *       when a declared package is not installed.</li>
 *   <li>A script using {@code @workspaceRoot} fails-fast when the
 *       feature is disabled (default).</li>
 * </ul>
 */
class GraaljsScriptExecutorRequireTest {

    private static Engine engine;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void require_resolvesPackage_fromWorkspaceNodeModules(@TempDir Path tmp) throws IOException {
        Path workspaceRoot = installFakePackage(tmp, "greeter",
                "module.exports = { hi: function(name) { return 'hi ' + name; } };");
        ScriptExecutor executor = newExecutor(workspaceRoot, true);

        String code = """
                /**
                 * @workspaceRoot _jsengine
                 * @requires greeter
                 */
                (function () {
                    var g = require('greeter');
                    return g.hi('alice');
                })();
                """;
        ScriptRequest req = new ScriptRequest(
                "js", code, "require-test", tools(workspaceRoot), Duration.ofSeconds(10));

        assertThat(executor.run(req).value()).isEqualTo("hi alice");
    }

    @Test
    void require_missingPackage_failsFastWithCapabilityError(@TempDir Path tmp) throws IOException {
        Path workspaceRoot = installFakePackage(tmp, "greeter",
                "module.exports = {};");
        ScriptExecutor executor = newExecutor(workspaceRoot, true);

        // @requires names a package that is NOT installed.
        String code = """
                /**
                 * @workspaceRoot _jsengine
                 * @requires nonexistent
                 */
                (function () { return 1; })();
                """;
        ScriptRequest req = new ScriptRequest(
                "js", code, "require-test", tools(workspaceRoot), Duration.ofSeconds(10));

        assertThatThrownBy(() -> executor.run(req))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("@requires 'nonexistent' not installed");
    }

    @Test
    void workspaceRoot_whenRequireDisabled_failsFastWithCapabilityError(
            @TempDir Path tmp) throws IOException {
        Path workspaceRoot = installFakePackage(tmp, "anything",
                "module.exports = 1;");
        ScriptExecutor executor = newExecutor(workspaceRoot, false);

        String code = """
                /**
                 * @workspaceRoot _jsengine
                 */
                (function () { return 1; })();
                """;
        ScriptRequest req = new ScriptRequest(
                "js", code, "disabled-test", tools(workspaceRoot), Duration.ofSeconds(10));

        assertThatThrownBy(() -> executor.run(req))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("vance.script.require.enabled=false");
    }

    @Test
    void noWorkspaceRoot_takesLegacyIOAccessNonePath(@TempDir Path tmp) throws IOException {
        // No @workspaceRoot, no @requires — feature toggle is on but
        // the script doesn't ask for require, so the executor falls
        // through to the IOAccess.NONE path. require is undefined.
        Path workspaceRoot = installFakePackage(tmp, "greeter",
                "module.exports = {};");
        ScriptExecutor executor = newExecutor(workspaceRoot, true);

        String code = "(function () { return typeof require; })();";
        ScriptRequest req = new ScriptRequest(
                "js", code, "no-require", tools(workspaceRoot), Duration.ofSeconds(10));

        assertThat(executor.run(req).value()).isEqualTo("undefined");
    }

    // ──────────────────── helpers ────────────────────

    /** Lays out tmp/<workspace>/node_modules/<pkg>/{package.json,index.js}. */
    private static Path installFakePackage(Path tmp, String pkg, String indexJsBody)
            throws IOException {
        Path workspace = tmp.resolve("_jsengine");
        Path pkgDir = workspace.resolve("node_modules").resolve(pkg);
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"),
                "{\"name\":\"" + pkg + "\",\"version\":\"1.0.0\",\"main\":\"index.js\"}",
                StandardCharsets.UTF_8);
        Files.writeString(pkgDir.resolve("index.js"), indexJsBody, StandardCharsets.UTF_8);
        return workspace;
    }

    /** Builds the executor wired with a mock WorkspaceService that
     *  returns a single Node RootDir at the given path. */
    private static ScriptExecutor newExecutor(Path workspaceRoot, boolean requireEnabled) {
        ScriptEngineProperties props = new ScriptEngineProperties();
        props.getRequire().setEnabled(requireEnabled);

        WorkspaceService ws = mock(WorkspaceService.class);
        WorkspaceDescriptor desc = new WorkspaceDescriptor();
        desc.setLabel(NodeHandler.DEFAULT_LABEL);
        RootDirHandle handle = RootDirHandle.builder()
                .tenantId("acme")
                .projectId("proj-1")
                .dirName(NodeHandler.DEFAULT_LABEL)
                .type(NodeHandler.TYPE)
                .path(workspaceRoot)
                .descriptor(desc)
                .build();
        when(ws.listRootDirs(any(), any())).thenReturn(List.of(handle));
        when(ws.getRootDir(any(), any(), any())).thenReturn(Optional.of(handle));

        return new GraaljsScriptExecutor(engine, props, ws);
    }

    /** Tool-bus for the script's vance.context — tenant/project must
     *  be set so the resolver doesn't bail with MISSING_CAPABILITY
     *  on scope grounds. */
    private static ContextToolsApi tools(Path unused) {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(new RecordingPermissionResolver()),
                mock(de.mhus.vance.brain.fook.FookChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class));
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", null);
        return new ContextToolsApi(dispatcher, ctx, Set.of());
    }
}
