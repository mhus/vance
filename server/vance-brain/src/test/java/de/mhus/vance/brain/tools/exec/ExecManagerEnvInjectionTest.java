package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.exec.ExecEnvPolicy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Integration-style: verifies that every exec job runs with a <b>sealed</b>
 * environment — the non-secret allow-list base plus explicitly injected vars,
 * and nothing else from the Brain JVM. This is the regression for GitHub issue
 * {@code mhus/vance#62} (an agent-driven command could read the Mongo password
 * and other secrets from the container environment).
 *
 * <p>The leak check is deliberately positive-shaped: the child's variables
 * must be a subset of the policy base (plus injected names and the few vars a
 * POSIX shell sets for itself), so a newly added credential variable is caught
 * without naming it here.
 */
@DisabledOnOs(OS.WINDOWS)
class ExecManagerEnvInjectionTest {

    private static final String TENANT = "t-1";
    private static final String PROJECT = "p-1";
    private static final String DIR = "ws";

    /** Variables a POSIX shell adds to its own environment on startup. */
    private static final Set<String> SHELL_NATIVE = Set.of("PWD", "SHLVL", "_", "OLDPWD");

    private ExecManager manager;

    @BeforeEach
    void setUp(@TempDir Path workDir, @TempDir Path execBase) {
        EngineMessageRouter router = mock(EngineMessageRouter.class);
        when(router.dispatch(any(), any(), any())).thenReturn(true);
        ExecutionRegistryService registry = mock(ExecutionRegistryService.class);
        WorkspaceService workspace = mock(WorkspaceService.class);

        RootDirHandle handle = mock(RootDirHandle.class);
        when(handle.getPath()).thenReturn(workDir);
        when(workspace.getRootDir(TENANT, PROJECT, DIR)).thenReturn(Optional.of(handle));

        ExecProperties props = new ExecProperties();
        props.setBaseDir(execBase.toString());
        props.setDefaultWaitMs(5_000);
        props.setCompletionTailLines(5);

        @SuppressWarnings("unchecked")
        ObjectProvider<EngineMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(router);

        manager = new ExecManager(props, new ExecEnvironmentBuilder(props), workspace, registry, provider);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void defaultEnv_subprocessSeesOnlyTheSealedBase() throws Exception {
        ExecJob job = manager.submit(TENANT, PROJECT, null, DIR, "env", SubmitOptions.defaults());
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        Map<String, String> childEnv = parseEnv(job.readStdout());
        assertThat(childEnv).isNotEmpty();
        // #62: nothing from the Brain JVM env beyond the non-secret base.
        assertOnlyExpected(childEnv, Set.of());
    }

    @Test
    void extras_subprocessSeesInjectedVarsOnTopOfTheBase() throws Exception {
        SubmitOptions options = SubmitOptions.defaults().withEnv(Map.of("VANCE_TEST_TOKEN", "secret-abc"));

        ExecJob job = manager.submit(TENANT, PROJECT, null, DIR, "env", options);
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        Map<String, String> childEnv = parseEnv(job.readStdout());
        assertThat(childEnv).containsEntry("VANCE_TEST_TOKEN", "secret-abc");
        assertOnlyExpected(childEnv, Set.of("VANCE_TEST_TOKEN"));
    }

    @Test
    void extras_pinBaseValuesLikePath() throws Exception {
        // The script path pins its own PATH — an extra must win over the base.
        SubmitOptions options = SubmitOptions.defaults().withEnv(Map.of("PATH", "/pinned/bin"));

        ExecJob job = manager.submit(TENANT, PROJECT, null, DIR, "echo PATH=${PATH:-<missing>}", options);
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        assertThat(job.readStdout()).contains("PATH=/pinned/bin");
    }

    @Test
    void labels_storedOnJobAndImmutable() {
        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_CORTEX,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON);
        SubmitOptions options = SubmitOptions.defaults().withLabels(labels);

        ExecJob job = manager.submit(TENANT, PROJECT, null, DIR, "true", options);
        manager.waitFor(job, 5_000);

        assertThat(job.labels())
                .containsEntry(ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_CORTEX)
                .containsEntry(ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON);
        // Defensive copy — mutating the source map shouldn't affect the job.
        assertThat(job.labels()).isUnmodifiable();
    }

    private static void assertOnlyExpected(Map<String, String> childEnv, Set<String> injected) {
        for (String key : childEnv.keySet()) {
            assertThat(ExecEnvPolicy.INHERITABLE_NAMES.contains(key)
                            || SHELL_NATIVE.contains(key)
                            || injected.contains(key))
                    .as("unexpected variable leaked into the subprocess env: " + key)
                    .isTrue();
        }
    }

    private static Map<String, String> parseEnv(String stdout) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : stdout.split("\n")) {
            int idx = line.indexOf('=');
            if (idx > 0) {
                out.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
        return out;
    }
}
