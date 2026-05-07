package de.mhus.vance.brain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.execution.ExecEvent;
import de.mhus.vance.api.execution.ExecListSnapshot;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class ExecEventHandlerTest {

    private ExecutionRegistryService registry;
    private ExecEventHandler handler;
    private ExecListSnapshotHandler snapshotHandler;
    private WebSocketSender sender;
    private WebSocketSession wsSession;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        registry = new ExecutionRegistryService();
        ObjectMapper mapper = new ObjectMapper();
        sender = mock(WebSocketSender.class);
        handler = new ExecEventHandler(mapper, sender, registry);
        snapshotHandler = new ExecListSnapshotHandler(mapper, sender, registry);
        wsSession = mock(WebSocketSession.class);
        ctx = new ConnectionContext(
                "acme", "alice", null, "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
    }

    @Test
    void started_eventRegistersFootEntryWithTenantFromCtx() throws IOException {
        ExecEvent ev = ExecEvent.builder()
                .kind(ExecEvent.Kind.STARTED.name())
                .executionId("e-1")
                .command("sleep 30")
                .sessionId("sess-1")
                .projectId("proj-x")
                .startedAt(Instant.now())
                .lastOutputAt(Instant.now())
                .build();

        handler.handle(ctx, wsSession, WebSocketEnvelope.notification("exec-event", ev));

        ExecutionRegistryEntry entry = registry.find("e-1").orElseThrow();
        assertThat(entry.tenantId()).isEqualTo("acme");
        assertThat(entry.sessionId()).isEqualTo("sess-1");
        assertThat(entry.projectId()).isEqualTo("proj-x");
        assertThat(entry.owner()).isEqualTo(new ExecutionOwner.Foot("conn-1"));
        assertThat(entry.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void ended_eventTransitionsRegistryStatus() throws IOException {
        // Seed a STARTED entry
        registry.register(new ExecutionRegistryEntry(
                "e-1", new ExecutionOwner.Foot("conn-1"),
                "acme", "proj", "sess", null,
                "true", null,
                Instant.now(), Instant.now(), null,
                ExecutionStatus.RUNNING, null, null, null));

        ExecEvent ev = ExecEvent.builder()
                .kind(ExecEvent.Kind.ENDED.name())
                .executionId("e-1")
                .lastOutputAt(Instant.now())
                .endedAt(Instant.now())
                .status("COMPLETED")
                .exitCode(0)
                .build();

        handler.handle(ctx, wsSession, WebSocketEnvelope.notification("exec-event", ev));

        ExecutionRegistryEntry entry = registry.find("e-1").orElseThrow();
        assertThat(entry.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(entry.exitCode()).isEqualTo(0);
        assertThat(entry.endedAt()).isNotNull();
    }

    @Test
    void invalidEvent_returnsErrorAndDoesNotRegister() throws IOException {
        handler.handle(ctx, wsSession,
                WebSocketEnvelope.notification("exec-event",
                        ExecEvent.builder().build()));

        verify(sender).sendError(
                org.mockito.ArgumentMatchers.eq(wsSession),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(400),
                org.mockito.ArgumentMatchers.contains("kind and executionId"));
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void snapshot_replacesAllEntriesForThisFootClient() throws IOException {
        // Seed a stale entry from a prior incarnation
        registry.register(new ExecutionRegistryEntry(
                "stale", new ExecutionOwner.Foot("conn-1"),
                "acme", "proj", "sess", null,
                "old", null,
                Instant.now(), Instant.now(), null,
                ExecutionStatus.RUNNING, null, null, null));
        // …and one from a different foot client which must survive
        registry.register(new ExecutionRegistryEntry(
                "other", new ExecutionOwner.Foot("conn-2"),
                "acme", "proj", "sess", null,
                "other", null,
                Instant.now(), Instant.now(), null,
                ExecutionStatus.RUNNING, null, null, null));

        ExecListSnapshot snap = ExecListSnapshot.builder()
                .executions(List.of(
                        ExecEvent.builder()
                                .kind(ExecEvent.Kind.STARTED.name())
                                .executionId("fresh-1")
                                .command("echo hi")
                                .startedAt(Instant.now())
                                .lastOutputAt(Instant.now())
                                .status("RUNNING")
                                .build()))
                .build();

        snapshotHandler.handle(ctx, wsSession,
                WebSocketEnvelope.notification("exec-list-snapshot", snap));

        assertThat(registry.find("stale")).isEmpty();
        assertThat(registry.find("fresh-1")).isPresent();
        assertThat(registry.find("other")).isPresent();
    }
}
