package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.damogran.DamogranManifest.OutputSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DamogranBuiltinTasksTest {

    private static DamogranContext workCtx(@Nullable ComposeExec exec) {
        return new DamogranContext("t", "p", "proc1", "ws", "ws", Path.of("/tmp/ws"),
                "WORK", null, null, null, null, exec, null);
    }

    // ──────────────────── exec ────────────────────

    @Test
    void exec_completedZeroExit_isSuccessWithStdoutLog() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(any(), anyInt())).thenReturn(new ComposeExec.Result("COMPLETED", 0, "hi", ""));

        DamogranTaskResult result = new ExecDamogranTask()
                .execute(workCtx(exec), new TaskSpec("exec", Map.of("command", "echo hi"), List.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.log()).isEqualTo("hi");
        verify(exec).run(eq("echo hi"), anyInt());
    }

    @Test
    void exec_nonZeroExit_isFailureWithDetail() {
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(any(), anyInt())).thenReturn(new ComposeExec.Result("COMPLETED", 1, "", "boom"));

        DamogranTaskResult result = new ExecDamogranTask()
                .execute(workCtx(exec), new TaskSpec("exec", Map.of("command", "false"), List.of()));

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("exit=1").contains("boom");
    }

    // ──────────────────── llm ────────────────────

    @Test
    void llm_writesReplyToDeclaredOutput_andReturnsArtifact() {
        LightLlmService lightLlm = mock(LightLlmService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(lightLlm.call(any(LightLlmRequest.class))).thenReturn("REPLY");

        TaskSpec spec = new TaskSpec("llm",
                Map.of("recipe", "analyze", "prompt", "go"),
                List.of(new OutputSpec("summary.md", null, null)));

        DamogranTaskResult result = new LlmDamogranTask(lightLlm, workspaceService).execute(workCtx(null), spec);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).singleElement()
                .satisfies(a -> {
                    assertThat(a.path()).isEqualTo("summary.md");
                    assertThat(a.kind()).isEqualTo("markdown");
                });
        verify(workspaceService).write("t", "p", "ws", "summary.md", "REPLY");
    }

    @Test
    void llm_withoutDeclaredOutput_fails() {
        LightLlmService lightLlm = mock(LightLlmService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);

        TaskSpec spec = new TaskSpec("llm", Map.of("recipe", "analyze", "prompt", "go"), List.of());

        DamogranTaskResult result = new LlmDamogranTask(lightLlm, workspaceService).execute(workCtx(null), spec);

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("output file");
        verify(lightLlm, never()).call(any());
    }

    // ──────────────────── python ────────────────────

    @Test
    void python_withoutScriptOrCode_fails() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);

        DamogranTaskResult result = new PythonDamogranTask(workspaceService)
                .execute(workCtx(null), new TaskSpec("python", Map.of(), List.of()));

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("script").contains("code");
    }

    @Test
    void python_inlineCode_writesFileAndRunsInterpreter() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        ComposeExec exec = mock(ComposeExec.class);
        when(exec.run(any(), anyInt())).thenReturn(new ComposeExec.Result("COMPLETED", 0, "ok", ""));

        DamogranTaskResult result = new PythonDamogranTask(workspaceService)
                .execute(workCtx(exec), new TaskSpec("python", Map.of("code", "print('x')"), List.of()));

        assertThat(result.isSuccess()).isTrue();
        verify(workspaceService).write(eq("t"), eq("p"), eq("ws"), eq(".damogran/inline.py"), eq("print('x')"));
        verify(exec).run(eq("python3 '.damogran/inline.py'"), anyInt());
    }
}
