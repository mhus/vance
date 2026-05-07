package de.mhus.vance.brain.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionRegistryServiceTest {

    private ExecutionRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = new ExecutionRegistryService();
    }

    @Test
    void register_thenFind_roundtrips() {
        registry.register(brainEntry("e1", "acme", "proj", ExecutionStatus.RUNNING));

        assertThat(registry.find("e1"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.executionId()).isEqualTo("e1"));
    }

    @Test
    void updateProgress_replacesScalarFields_keepsScopeAndOwner() {
        registry.register(brainEntry("e1", "acme", "proj", ExecutionStatus.RUNNING));
        Instant ended = Instant.now();

        registry.updateProgress("e1", ended, ExecutionStatus.COMPLETED, 0, ended);

        ExecutionRegistryEntry e = registry.find("e1").orElseThrow();
        assertThat(e.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(e.exitCode()).isEqualTo(0);
        assertThat(e.endedAt()).isEqualTo(ended);
        assertThat(e.tenantId()).isEqualTo("acme");
        assertThat(e.owner()).isEqualTo(ExecutionOwner.Brain.INSTANCE);
    }

    @Test
    void updateProgress_unknownId_isNoOp() {
        registry.updateProgress("ghost", Instant.now(), ExecutionStatus.COMPLETED, 0, Instant.now());

        assertThat(registry.find("ghost")).isEmpty();
    }

    @Test
    void list_filtersByProjectAndOnlyRunning() {
        registry.register(brainEntry("a", "t", "p1", ExecutionStatus.RUNNING));
        registry.register(brainEntry("b", "t", "p1", ExecutionStatus.COMPLETED));
        registry.register(brainEntry("c", "t", "p2", ExecutionStatus.RUNNING));

        List<ExecutionRegistryEntry> all = registry.list(ExecutionScopeFilter.forProject("t", "p1"));
        assertThat(all).extracting(ExecutionRegistryEntry::executionId)
                .containsExactlyInAnyOrder("a", "b");

        List<ExecutionRegistryEntry> runningOnly = registry.list(
                new ExecutionScopeFilter("t", "p1", null, null, null, true));
        assertThat(runningOnly).extracting(ExecutionRegistryEntry::executionId)
                .containsExactly("a");
    }

    @Test
    void removeByFootClient_dropsOnlyMatchingFootEntries() {
        registry.register(brainEntry("brain-1", "t", "p", ExecutionStatus.RUNNING));
        registry.register(footEntry("foot-1", "client-A", "t", "p", ExecutionStatus.RUNNING));
        registry.register(footEntry("foot-2", "client-B", "t", "p", ExecutionStatus.RUNNING));

        int dropped = registry.removeByFootClient("client-A");

        assertThat(dropped).isEqualTo(1);
        assertThat(registry.find("foot-1")).isEmpty();
        assertThat(registry.find("brain-1")).isPresent();
        assertThat(registry.find("foot-2")).isPresent();
    }

    private ExecutionRegistryEntry brainEntry(
            String id, String tenantId, String projectId, ExecutionStatus status) {
        Instant now = Instant.now();
        return new ExecutionRegistryEntry(
                id, ExecutionOwner.Brain.INSTANCE,
                tenantId, projectId, "sess", null,
                "true", null,
                now, now, status == ExecutionStatus.RUNNING ? null : now,
                status, status == ExecutionStatus.RUNNING ? null : 0,
                "stdout.log", "stderr.log");
    }

    private ExecutionRegistryEntry footEntry(
            String id, String clientId, String tenantId, String projectId,
            ExecutionStatus status) {
        Instant now = Instant.now();
        return new ExecutionRegistryEntry(
                id, new ExecutionOwner.Foot(clientId),
                tenantId, projectId, "sess", null,
                "true", null,
                now, now, status == ExecutionStatus.RUNNING ? null : now,
                status, status == ExecutionStatus.RUNNING ? null : 0,
                "stdout.log", "stderr.log");
    }
}
