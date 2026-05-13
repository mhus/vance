package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecCheckToolTest {

    private static final String TENANT = "t-1";
    private static final String PROJECT = "p-1";
    private static final String JOB_ID = "j-abc";

    private ExecManager execManager;
    private ExecCheckTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        execManager = mock(ExecManager.class);
        ExecProperties props = new ExecProperties();
        tool = new ExecCheckTool(execManager, props);
        ctx = new ToolInvocationContext(TENANT, PROJECT, "s-1", "proc-1", "u-1");
    }

    private ExecJob runningJob() {
        ExecJob job = new ExecJob(
                JOB_ID, PROJECT, "proc-1", "sleep 100",
                Path.of("/nowhere/stdout.log"), Path.of("/nowhere/stderr.log"));
        job.initialDeadline(Instant.now().plusSeconds(30));
        return job;
    }

    private ExecJob terminalJob() {
        ExecJob job = runningJob();
        job.exitCode(0);
        job.status(ExecJob.Status.COMPLETED);
        job.finishedAt(Instant.now());
        return job;
    }

    @Test
    void invoke_missingId_throws() {
        assertThatThrownBy(() ->
                tool.invoke(Map.of("ifRunning", "wait"), ctx))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void invoke_missingIfRunning_throws() {
        assertThatThrownBy(() ->
                tool.invoke(Map.of("id", JOB_ID), ctx))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void invoke_invalidIfRunning_throws() {
        assertThatThrownBy(() ->
                tool.invoke(Map.of("id", JOB_ID, "ifRunning", "bogus"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("one of");
    }

    @Test
    void invoke_extendWithoutSeconds_throws() {
        when(execManager.get(TENANT, PROJECT, JOB_ID))
                .thenReturn(Optional.of(runningJob()));

        assertThatThrownBy(() ->
                tool.invoke(Map.of("id", JOB_ID, "ifRunning", "extend"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("extendSeconds");
    }

    @Test
    void invoke_unknownJob_throws() {
        when(execManager.get(TENANT, PROJECT, JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                tool.invoke(Map.of("id", JOB_ID, "ifRunning", "wait"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void extend_runningJob_callsExtendDeadlineAndReturnsNewDeadline() {
        ExecJob job = runningJob();
        when(execManager.get(TENANT, PROJECT, JOB_ID)).thenReturn(Optional.of(job));
        when(execManager.extendDeadline(eq(TENANT), eq(PROJECT), eq(JOB_ID), any()))
                .thenAnswer(inv -> {
                    job.extendDeadline(Instant.now().plus(inv.getArgument(3)));
                    return true;
                });

        Map<String, Object> out = tool.invoke(Map.of(
                "id", JOB_ID,
                "ifRunning", "extend",
                "extendSeconds", 60), ctx);

        assertThat(out).containsEntry("decision", "extend");
        assertThat(out).containsEntry("terminal", false);
        assertThat(out).containsKey("newDeadline");
        verify(execManager).extendDeadline(
                TENANT, PROJECT, JOB_ID, Duration.ofSeconds(60));
    }

    @Test
    void kill_runningJob_callsKill() {
        ExecJob job = runningJob();
        when(execManager.get(TENANT, PROJECT, JOB_ID)).thenReturn(Optional.of(job));
        when(execManager.kill(TENANT, PROJECT, JOB_ID)).thenReturn(true);

        Map<String, Object> out = tool.invoke(Map.of(
                "id", JOB_ID, "ifRunning", "kill"), ctx);

        assertThat(out).containsEntry("decision", "kill");
        assertThat(out).containsEntry("killApplied", true);
        verify(execManager).kill(TENANT, PROJECT, JOB_ID);
    }

    @Test
    void wait_runningJob_doesNothing_returnsDeadline() {
        ExecJob job = runningJob();
        when(execManager.get(TENANT, PROJECT, JOB_ID)).thenReturn(Optional.of(job));

        Map<String, Object> out = tool.invoke(Map.of(
                "id", JOB_ID, "ifRunning", "wait"), ctx);

        assertThat(out).containsEntry("decision", "wait");
        assertThat(out).containsEntry("terminal", false);
        assertThat(out).containsKey("deadline");
        verify(execManager, never()).extendDeadline(any(), any(), any(), any());
        verify(execManager, never()).kill(any(), any(), any());
    }

    @Test
    void terminalJob_ignoresIfRunning_returnsFinalStatusWithTerminalMarker() {
        ExecJob job = terminalJob();
        when(execManager.get(TENANT, PROJECT, JOB_ID)).thenReturn(Optional.of(job));

        Map<String, Object> out = tool.invoke(Map.of(
                "id", JOB_ID, "ifRunning", "extend",
                "extendSeconds", 60), ctx);

        assertThat(out).containsEntry("status", "COMPLETED");
        assertThat(out).containsEntry("terminal", true);
        assertThat(out).containsEntry("decision", "extend");
        verify(execManager, never()).extendDeadline(any(), any(), any(), any());
    }
}
