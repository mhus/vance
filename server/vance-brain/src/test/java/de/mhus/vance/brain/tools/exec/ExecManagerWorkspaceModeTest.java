package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/** Every exec job funnels through {@link ExecManager#submit} — the one gate for {@code vance.workspace.mode=off}. */
class ExecManagerWorkspaceModeTest {

    @Test
    void submit_withWorkspaceOff_isRefusedBeforeAnythingRuns(@TempDir Path execBase) {
        WorkspaceService workspace = mock(WorkspaceService.class);
        when(workspace.mode()).thenReturn(WorkspaceMode.OFF);
        ExecProperties props = new ExecProperties();
        props.setBaseDir(execBase.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<EngineMessageRouter> router = mock(ObjectProvider.class);
        ExecManager manager = new ExecManager(
                props,
                new ExecEnvironmentBuilder(props, ExecTestHomes.homes(execBase.resolve("homes"))),
                workspace,
                mock(ExecutionRegistryService.class),
                router);
        try {
            assertThatThrownBy(() -> manager.submit("acme", "proj", null, "dir", "echo hi", SubmitOptions.defaults()))
                    .isInstanceOf(ExecException.class)
                    .hasMessageContaining("vance.workspace.mode=off");
        } finally {
            manager.shutdown();
        }
    }
}
