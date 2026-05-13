package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.api.execution.ExecEvent;
import de.mhus.vance.api.execution.ExecListSnapshot;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.ConnectionService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sends {@link MessageType#EXEC_EVENT} and {@link
 * MessageType#EXEC_LIST_SNAPSHOT} frames to the brain so its
 * cross-side execution registry stays in sync with foot-side jobs.
 *
 * <p>Sends are best-effort: if the connection is down the event is
 * dropped silently. The brain catches up the next time the foot
 * reconnects via the snapshot send-on-bind.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FootExecEventDispatcher {

    /** Lazy lookup — {@link ConnectionService} depends on services that read this one. */
    private final ObjectProvider<ConnectionService> connectionService;

    /** Lazy lookup — {@link ClientExecutorService} owns the dispatcher, breaking the cycle. */
    private final ObjectProvider<ClientExecutorService> executor;

    /** Snapshot every locally known job and push it to the brain for reconciliation. */
    public void publishCurrentSnapshot() {
        ClientExecutorService e = executor.getIfAvailable();
        if (e == null) return;
        publishSnapshot(e.snapshot());
    }

    public void publishStarted(ClientExecJob job) {
        publish(buildStarted(job));
    }

    public void publishEnded(ClientExecJob job) {
        ExecEvent event = ExecEvent.builder()
                .kind(ExecEvent.Kind.ENDED.name())
                .executionId(job.id())
                .lastOutputAt(job.lastOutputAt())
                .endedAt(job.finishedAt())
                .status(job.status().name())
                .exitCode(job.exitCode())
                .timedOut(job.timedOut() ? Boolean.TRUE : null)
                .build();
        publish(event);
    }

    /** Send a snapshot of every job we currently know about. */
    public void publishSnapshot(Collection<ClientExecJob> jobs) {
        List<ExecEvent> entries = new ArrayList<>(jobs.size());
        for (ClientExecJob j : jobs) {
            entries.add(buildStartedWithStatus(j));
        }
        ExecListSnapshot payload = ExecListSnapshot.builder()
                .executions(entries)
                .build();
        send(MessageType.EXEC_LIST_SNAPSHOT, payload);
    }

    private static ExecEvent buildStarted(ClientExecJob job) {
        return ExecEvent.builder()
                .kind(ExecEvent.Kind.STARTED.name())
                .executionId(job.id())
                .command(job.command())
                .sessionId(job.sessionId())
                .projectId(job.projectId())
                .startedAt(job.startedAt())
                .lastOutputAt(job.lastOutputAt())
                .stdoutPath(job.stdoutFile().toString())
                .stderrPath(job.stderrFile().toString())
                .build();
    }

    /** Snapshot entry: STARTED-shape plus current status (so brain sees terminal jobs as such). */
    private static ExecEvent buildStartedWithStatus(ClientExecJob job) {
        ExecEvent ev = buildStarted(job);
        ev.setStatus(job.status().name());
        ev.setExitCode(job.exitCode());
        ev.setEndedAt(job.finishedAt());
        return ev;
    }

    private void publish(ExecEvent event) {
        send(MessageType.EXEC_EVENT, event);
    }

    private void send(String type, Object payload) {
        ConnectionService cs = connectionService.getIfAvailable();
        if (cs == null || !cs.isOpen()) {
            return;
        }
        try {
            cs.send(WebSocketEnvelope.notification(type, payload));
        } catch (RuntimeException e) {
            log.debug("exec event '{}' send failed: {}", type, e.toString());
        }
    }
}
