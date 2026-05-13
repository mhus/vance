package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Integration-style: runs a real (very short) shell command via
 * {@link ExecManager}, then asserts the natural-completion push hits
 * {@link EngineMessageRouter} with the expected event.
 */
class ExecManagerPushCompletionTest {

    private static final String TENANT = "t-1";
    private static final String PROJECT = "p-1";
    private static final String OWNER = "proc-7";
    private static final String DIR = "workspace";

    private EngineMessageRouter router;
    private ExecutionRegistryService registry;
    private WorkspaceService workspace;
    private ExecManager manager;

    @BeforeEach
    void setUp(@TempDir Path workDir, @TempDir Path execBase) {
        router = mock(EngineMessageRouter.class);
        when(router.dispatch(any(), any(), any())).thenReturn(true);
        registry = mock(ExecutionRegistryService.class);
        workspace = mock(WorkspaceService.class);

        RootDirHandle handle = mock(RootDirHandle.class);
        when(handle.getPath()).thenReturn(workDir);
        when(workspace.getRootDir(TENANT, PROJECT, DIR)).thenReturn(Optional.of(handle));

        ExecProperties props = new ExecProperties();
        props.setBaseDir(execBase.toString());
        props.setDefaultWaitMs(5_000);
        props.setCompletionTailLines(10);

        @SuppressWarnings("unchecked")
        ObjectProvider<EngineMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(router);

        manager = new ExecManager(props, workspace, registry, provider);
        // ExecJobRenderer + tailFile need ExecProperties via the
        // {@code properties} field; lombok's @RequiredArgsConstructor
        // sets it through the constructor — no reflection needed.
        ReflectionTestUtils.setField(manager, "properties", props);
    }

    @Test
    void submitWithOwner_dispatchesExecFinishedOnNaturalCompletion() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(OWNER), eq(OWNER), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        ExecJob job = manager.submit(TENANT, PROJECT, OWNER, DIR, "echo hello");
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PendingMessageDocument> docCaptor =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router, times(1)).dispatch(eq(OWNER), eq(OWNER), docCaptor.capture());
        PendingMessageDocument doc = docCaptor.getValue();
        assertThat(doc.getType()).isEqualTo(PendingMessageType.PROCESS_EVENT);
        assertThat(doc.getEventType()).isEqualTo(ProcessEventType.EXEC_FINISHED);
        assertThat(doc.getSourceProcessId()).isEqualTo(OWNER);
        assertThat(doc.getContent()).contains(job.id()).contains("completed");
        Map<String, Object> payload = doc.getPayload();
        assertThat(payload).containsEntry("jobId", job.id());
        assertThat(payload).containsEntry("status", "COMPLETED");
        assertThat(payload).containsEntry("exitCode", 0);
        assertThat(payload).containsEntry("projectId", PROJECT);
    }

    @Test
    void submitWithoutOwner_doesNotDispatch() throws Exception {
        ExecJob job = manager.submit(TENANT, PROJECT, DIR, "echo nobody");
        manager.waitFor(job, 5_000);

        assertThat(job.isTerminal()).isTrue();
        // Give the worker thread a moment to traverse the finally.
        Thread.sleep(100);
        verify(router, never()).dispatch(any(), any(), any());
    }

    @Test
    void failedJob_dispatchedWithFailedStatus() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(OWNER), eq(OWNER), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        ExecJob job = manager.submit(TENANT, PROJECT, OWNER, DIR, "false");
        manager.waitFor(job, 5_000);

        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<PendingMessageDocument> docCaptor =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router).dispatch(eq(OWNER), eq(OWNER), docCaptor.capture());
        Map<String, Object> payload = docCaptor.getValue().getPayload();
        assertThat(payload).containsEntry("status", "FAILED");
        assertThat(payload).containsEntry("exitCode", 1);
    }
}
