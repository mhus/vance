package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Integration-style: runs real subprocesses to verify the watchdog
 * kills long-running jobs at their deadline and that
 * {@link ExecManager#extendDeadline} pushes the kill out.
 */
class ExecManagerWatchdogTest {

    private static final String TENANT = "t-1";
    private static final String PROJECT = "p-1";
    private static final String OWNER = "proc-1";
    private static final String DIR = "ws";

    private EngineMessageRouter router;
    private ExecManager manager;

    @BeforeEach
    void setUp(@TempDir Path workDir, @TempDir Path execBase) {
        router = mock(EngineMessageRouter.class);
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
        ReflectionTestUtils.setField(manager, "properties", props);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void watchdog_killsRunawayJob_andEmitsExecTimeout() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(OWNER), eq(OWNER), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        Instant deadline = Instant.now().plusSeconds(1);
        ExecJob job = manager.submit(
                TENANT, PROJECT, OWNER, DIR, "sleep 10", deadline);

        // Watchdog has 1s; give it generous slack.
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(job.status()).isEqualTo(ExecJob.Status.KILLED);
        assertThat(job.killedByWatchdog()).isTrue();

        ArgumentCaptor<PendingMessageDocument> cap =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router).dispatch(eq(OWNER), eq(OWNER), cap.capture());
        PendingMessageDocument doc = cap.getValue();
        assertThat(doc.getEventType()).isEqualTo(ProcessEventType.EXEC_TIMEOUT);
        assertThat(doc.getContent()).contains("timed out");
        assertThat(doc.getPayload()).containsKey("killedAfterSeconds");
        assertThat(doc.getPayload()).containsEntry("status", "KILLED");
    }

    @Test
    void extendDeadline_postponesWatchdogKill() throws Exception {
        Instant deadline = Instant.now().plusMillis(500);
        ExecJob job = manager.submit(
                TENANT, PROJECT, OWNER, DIR, "sleep 3", deadline);

        // Push the deadline out before the original watchdog would fire.
        Thread.sleep(200);
        boolean extended = manager.extendDeadline(
                TENANT, PROJECT, job.id(), Duration.ofSeconds(5));
        assertThat(extended).isTrue();

        // Wait past the original deadline; the job must still be RUNNING.
        Thread.sleep(700);
        assertThat(job.isTerminal()).isFalse();
        assertThat(job.killedByWatchdog()).isFalse();

        // Let it finish naturally — sleep 3s — and verify it's EXEC_FINISHED
        // (the extend was applied past natural completion).
        manager.waitFor(job, 5_000);
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.killedByWatchdog()).isFalse();
    }

    @Test
    void extendDeadline_returnsFalseForTerminalJob() throws Exception {
        ExecJob job = manager.submit(
                TENANT, PROJECT, OWNER, DIR, "echo done", null);
        manager.waitFor(job, 5_000);
        assertThat(job.isTerminal()).isTrue();

        boolean extended = manager.extendDeadline(
                TENANT, PROJECT, job.id(), Duration.ofSeconds(10));
        assertThat(extended).isFalse();
    }

    @Test
    void extendDeadline_unknownJob_returnsFalse() {
        boolean extended = manager.extendDeadline(
                TENANT, PROJECT, "no-such-id", Duration.ofSeconds(10));
        assertThat(extended).isFalse();
    }

    @Test
    void naturalCompletion_emitsExecFinished_notTimeout() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(OWNER), eq(OWNER), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        Instant deadline = Instant.now().plusSeconds(10); // plenty of slack
        ExecJob job = manager.submit(
                TENANT, PROJECT, OWNER, DIR, "echo quick", deadline);
        manager.waitFor(job, 5_000);

        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PendingMessageDocument> cap =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router).dispatch(eq(OWNER), eq(OWNER), cap.capture());
        assertThat(cap.getValue().getEventType())
                .isEqualTo(ProcessEventType.EXEC_FINISHED);
        assertThat(job.killedByWatchdog()).isFalse();
    }
}
