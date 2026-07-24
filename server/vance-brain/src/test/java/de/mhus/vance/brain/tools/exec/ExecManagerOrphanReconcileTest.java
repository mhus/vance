package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The orphan-reconciliation sweep ({@link ExecManager#reconcileOrphanedJobs})
 * and its {@link ExecManager#isStuckOrphan} predicate. Guards the case where a
 * worker thread died without running its {@code finally}, leaving a job stuck
 * {@code RUNNING} forever (pins the session, blocks the IdleSweeper). A job is
 * only reaped when its OS process is dead <em>and</em> it has been silent past
 * the TTL — a live-but-quiet long-running job must never be touched.
 */
class ExecManagerOrphanReconcileTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private EngineMessageRouter router;
    private ExecutionRegistryService registry;
    private ExecManager manager;
    private Process aliveProc;

    @BeforeEach
    void setUp() {
        router = mock(EngineMessageRouter.class);
        when(router.dispatch(any(), any(), any())).thenReturn(true);
        registry = mock(ExecutionRegistryService.class);
        WorkspaceService workspace = mock(WorkspaceService.class);

        ExecProperties props = new ExecProperties();
        props.setOrphanReconcileTtl(TTL);
        props.setCompletionTailLines(5);

        @SuppressWarnings("unchecked")
        ObjectProvider<EngineMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(router);

        manager = new ExecManager(props, workspace, registry, provider);
    }

    @AfterEach
    void tearDown() {
        if (aliveProc != null) {
            aliveProc.destroyForcibly();
        }
    }

    // ──────────────────── isStuckOrphan predicate ────────────────────

    @Test
    void isStuckOrphan_terminalJob_isNotStuck() throws Exception {
        ExecJob job = newJob(null);
        job.process(deadProcess());
        job.status(ExecJob.Status.COMPLETED);

        assertThat(ExecManager.isStuckOrphan(job, farFuture(), TTL)).isFalse();
    }

    @Test
    void isStuckOrphan_liveProcess_isNotStuck() {
        ExecJob job = newJob(null);
        job.process(aliveProcess());

        // Even far past the TTL: an alive process is a genuinely running job.
        assertThat(ExecManager.isStuckOrphan(job, farFuture(), TTL)).isFalse();
    }

    @Test
    void isStuckOrphan_deadProcessWithinTtl_isNotStuck() throws Exception {
        ExecJob job = newJob(null);
        job.process(deadProcess());

        // now ~ lastOutputAt (construction time) → dwell below the TTL.
        assertThat(ExecManager.isStuckOrphan(job, Instant.now(), TTL)).isFalse();
    }

    @Test
    void isStuckOrphan_deadProcessPastTtl_isStuck() throws Exception {
        ExecJob job = newJob(null);
        job.process(deadProcess());

        assertThat(ExecManager.isStuckOrphan(job, farFuture(), TTL)).isTrue();
    }

    @Test
    void isStuckOrphan_nullProcessPastTtl_isStuck() {
        // Worker died before ever assigning a process.
        ExecJob job = newJob(null);

        assertThat(ExecManager.isStuckOrphan(job, farFuture(), TTL)).isTrue();
    }

    // ──────────────────── markOrphanedIfRunning atomicity ────────────────────

    @Test
    void markOrphanedIfRunning_flipsOnce_thenNoOp() {
        ExecJob job = newJob(null);

        assertThat(job.markOrphanedIfRunning()).isTrue();
        assertThat(job.status()).isEqualTo(ExecJob.Status.ORPHANED);
        assertThat(job.finishedAt()).isNotNull();
        // A concurrent finally that already terminated the job wins — no clobber.
        assertThat(job.markOrphanedIfRunning()).isFalse();
        assertThat(job.status()).isEqualTo(ExecJob.Status.ORPHANED);
    }

    // ──────────────────── reconcileOrphanedJobs wiring ────────────────────

    @Test
    void reconcile_flipsStuckJob_mirrorsToRegistry_andNotifiesOwner() throws Exception {
        ExecJob job = newJob("proc-9");
        job.process(deadProcess());
        inject("t-1", "p-1", job);

        int reconciled = manager.reconcileOrphanedJobs(farFuture());

        assertThat(reconciled).isEqualTo(1);
        assertThat(job.status()).isEqualTo(ExecJob.Status.ORPHANED);

        verify(registry).updateProgress(
                eq(job.id()), any(), eq(ExecutionStatus.ORPHANED), any(), any());

        // Owner is unblocked with EXEC_FINISHED (orphan is not a watchdog kill).
        ArgumentCaptor<PendingMessageDocument> cap =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router).dispatch(eq("proc-9"), eq("proc-9"), cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(ProcessEventType.EXEC_FINISHED);
        assertThat(cap.getValue().getPayload()).containsEntry("status", "ORPHANED");
    }

    @Test
    void reconcile_leavesLiveJobUntouched() {
        ExecJob job = newJob("proc-9");
        job.process(aliveProcess());
        inject("t-1", "p-1", job);

        int reconciled = manager.reconcileOrphanedJobs(farFuture());

        assertThat(reconciled).isZero();
        assertThat(job.status()).isEqualTo(ExecJob.Status.RUNNING);
        verify(registry, never()).updateProgress(any(), any(), any(), any(), any());
        verify(router, never()).dispatch(any(), any(), any());
    }

    // ──────────────────── helpers ────────────────────

    private static ExecJob newJob(String ownerProcessId) {
        return new ExecJob("job-" + System.nanoTime(), "p-1", ownerProcessId,
                "sleep 999", Path.of("stdout.log"), Path.of("stderr.log"));
    }

    private static Instant farFuture() {
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    private static Process deadProcess() throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "exit 0").start();
        p.waitFor();
        return p;
    }

    private Process aliveProcess() {
        try {
            aliveProc = new ProcessBuilder("sh", "-c", "sleep 60").start();
            return aliveProc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void inject(String tenant, String project, ExecJob job) {
        Map<String, Map<String, ExecJob>> jobs =
                (Map<String, Map<String, ExecJob>>) ReflectionTestUtils.getField(manager, "jobs");
        Map<String, ExecJob> perProject =
                Collections.synchronizedMap(new LinkedHashMap<>());
        perProject.put(job.id(), job);
        jobs.put(tenant + "/" + project, perProject);
    }
}
