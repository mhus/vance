package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Integration-style: verifies that {@link SubmitOptions#env()} seals
 * the subprocess environment (only injected vars present, inherited
 * vars stripped) and that the default-null env preserves the legacy
 * inherit behaviour.
 */
@DisabledOnOs(OS.WINDOWS)
class ExecManagerEnvInjectionTest {

    private static final String TENANT = "t-1";
    private static final String PROJECT = "p-1";
    private static final String DIR = "ws";

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

        manager = new ExecManager(props, workspace, registry, provider);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void sealedEnv_subprocessSeesOnlyInjectedVars() throws Exception {
        SubmitOptions options = SubmitOptions.defaults()
                .withEnv(Map.of("VANCE_TEST_TOKEN", "secret-abc"));

        ExecJob job = manager.submit(
                TENANT, PROJECT, null, DIR,
                "echo TOKEN=${VANCE_TEST_TOKEN:-<missing>}; "
                        + "echo HOME=${HOME:-<missing>}",
                options);
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        String out = job.readStdout();
        // Injected var is present.
        assertThat(out).contains("TOKEN=secret-abc");
        // Inherited HOME is stripped — only the shell-default fallback
        // applies. Note: /bin/sh sets its own PATH default on startup
        // even when the env is fully cleared, so we can't assert
        // PATH=<missing> portably; HOME is the clean signal.
        assertThat(out).contains("HOME=<missing>");
    }

    @Test
    void nullEnv_subprocessInheritsJvmEnv() throws Exception {
        ExecJob job = manager.submit(
                TENANT, PROJECT, null, DIR,
                "echo HOME=${HOME:-<missing>}",
                SubmitOptions.defaults());
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        assertThat(job.readStdout()).contains("HOME=").doesNotContain("HOME=<missing>");
    }

    @Test
    void labels_storedOnJobAndImmutable() {
        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_CORTEX,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON);
        SubmitOptions options = SubmitOptions.defaults().withLabels(labels);

        ExecJob job = manager.submit(
                TENANT, PROJECT, null, DIR, "true", options);
        manager.waitFor(job, 5_000);

        assertThat(job.labels())
                .containsEntry(ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_CORTEX)
                .containsEntry(ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON);
        // Defensive copy — mutating the source map shouldn't affect the job.
        assertThat(job.labels()).isUnmodifiable();
    }
}
