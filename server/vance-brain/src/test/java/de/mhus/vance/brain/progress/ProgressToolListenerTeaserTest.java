package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.progress.UsageDelta;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The teaser contract of the progress pings: the call subject rides the
 * open ping, the outcome summary rides the close ping, and a delegated
 * backend leg stays silent because the wrapper's ping already names the
 * same subject.
 */
class ProgressToolListenerTeaserTest {

    private final ProgressEmitter emitter = mock(ProgressEmitter.class);
    private final LlmCallTracker tracker = mock(LlmCallTracker.class);

    private final ThinkProcessDocument process =
            ThinkProcessDocument.builder().id("proc-1").build();

    private ToolInvocationListener listener;

    @BeforeEach
    void setUp() {
        when(tracker.snapshot(any())).thenReturn(LlmCallTracker.Snapshot.ZERO);
        when(emitter.openOperation(any(), any(), any(), any(), any())).thenReturn("op-1");
        listener = new ProgressToolListener(emitter, tracker).forProcess(process);
    }

    @Test
    void toolCall_openPingCarriesTheCallTeaser() {
        listener.before("file_write", "a.txt, 2 lines, 7 chars");

        verify(emitter)
                .openOperation(
                        process,
                        StatusTag.TOOL_START,
                        "Calling tool: file_write",
                        "file_write",
                        "a.txt, 2 lines, 7 chars");
    }

    @Test
    void toolCall_closePingCarriesTheOutcomeTeaser() {
        listener.before("file_write", "a.txt, 2 lines, 7 chars");

        listener.after("file_write", 12, "Wrote 7 chars", null);

        verify(emitter)
                .closeOperation(
                        eq(process),
                        eq("op-1"),
                        eq(StatusTag.TOOL_END),
                        eq("Tool file_write done (12ms)"),
                        eq("file_write"),
                        any(UsageDelta.class),
                        eq("Wrote 7 chars"));
    }

    @Test
    void failedCall_reportsTheCauseAndNoOutcomeTeaser() {
        listener.before("file_write", "a.txt");

        listener.after("file_write", 12, null, new IllegalStateException("disk full"));

        ArgumentCaptor<StatusPayload> captor = ArgumentCaptor.forClass(StatusPayload.class);
        verify(emitter).emitStatus(eq(process), captor.capture());
        StatusPayload payload = captor.getValue();
        assertThat(payload.getFailed()).isTrue();
        assertThat(payload.getDetail()).isEqualTo("disk full");
        assertThat(payload.getTeaser()).isNull();
        assertThat(payload.getOperationId()).isEqualTo("op-1");
    }

    @Test
    void delegatedLeg_staysSilent() {
        // file_write → client_file_write is the same ask; the wrapper's ping
        // already carries the teaser, a second one would just be noise.
        listener.beforeDelegate("client_file_write", "a.txt");
        listener.afterDelegate("client_file_write", 12, "Wrote 7 chars", null);

        verifyNoInteractions(emitter);
    }

    @Test
    void closeWithoutOpen_degradesToAnUncorrelatedEndPing() {
        listener.after("file_write", 5, "Wrote 7 chars", null);

        ArgumentCaptor<StatusPayload> captor = ArgumentCaptor.forClass(StatusPayload.class);
        verify(emitter).emitStatus(eq(process), captor.capture());
        StatusPayload payload = captor.getValue();
        assertThat(payload.getTag()).isEqualTo(StatusTag.TOOL_END);
        assertThat(payload.getTeaser()).isEqualTo("Wrote 7 chars");
        assertThat(payload.getOperationId()).isNull();
    }
}
