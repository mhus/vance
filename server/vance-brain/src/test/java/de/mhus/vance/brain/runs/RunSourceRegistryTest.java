package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunSummaryDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunSourceRegistryTest {

    @Test
    void mergesSourcesNewestFirst() {
        RunSourceRegistry registry = registryOf(
                source("workflow", summary("workflow:a", Instant.parse("2024-01-01T10:00:00Z"))),
                source("process", summary("process:b", Instant.parse("2024-01-01T12:00:00Z"))));

        assertThat(registry.list("acme", "proj", 10))
                .extracting(RunSummaryDto::getRunId)
                .containsExactly("process:b", "workflow:a");
    }

    @Test
    void oneBrokenSourceDoesNotBlankTheList() {
        RunSourceRegistry registry = registryOf(
                new RunSource() {
                    @Override public String sourceId() { return "broken"; }
                    @Override public List<RunSummaryDto> list(String t, String p, int l) {
                        throw new IllegalStateException("mongo down");
                    }
                    @Override public Optional<RunDetailDto> get(String t, String p, String id) {
                        return Optional.empty();
                    }
                },
                source("workflow", summary("workflow:a", Instant.parse("2024-01-01T10:00:00Z"))));

        assertThat(registry.list("acme", "proj", 10)).hasSize(1);
    }

    @Test
    void theLimitIsTheWholeListNotPerSource() {
        // Asked for two, three sources each offering one: the caller must
        // get two, not "two times however many runtimes are registered" —
        // a number it has no way of knowing.
        RunSourceRegistry registry = registryOf(
                source("workflow", summary("workflow:a", Instant.parse("2024-01-01T10:00:00Z"))),
                source("process", summary("process:b", Instant.parse("2024-01-01T12:00:00Z"))),
                source("compose", summary("compose:c", Instant.parse("2024-01-01T11:00:00Z"))));

        assertThat(registry.list("acme", "proj", 2))
                .extracting(RunSummaryDto::getRunId)
                .containsExactly("process:b", "compose:c");
    }

    @Test
    void routesDetailToTheSourceNamedInTheId() {
        RunSourceRegistry registry = registryOf(
                source("workflow", summary("workflow:a", Instant.now())),
                source("process", summary("process:b", Instant.now())));

        assertThat(registry.get("acme", "proj", "process:b")).isPresent();
        // Unknown source and malformed id are both "no such run" — the
        // caller learns nothing about which sources exist.
        assertThat(registry.get("acme", "proj", "ghost:b")).isEmpty();
        assertThat(registry.get("acme", "proj", "nocolon")).isEmpty();
    }

    @Test
    void aSourceIdWithAColonIsRefusedAtStartup() {
        // It would make the composite id ambiguous, and the failure would
        // only show up later as a run that cannot be opened.
        assertThatThrownBy(() -> registryOf(source("we:ird", summary("x:y", Instant.now()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("we:ird");
    }

    private static RunSourceRegistry registryOf(RunSource... sources) {
        RunSourceRegistry registry = new RunSourceRegistry(List.of(sources));
        registry.collect();
        return registry;
    }

    private static RunSummaryDto summary(String runId, Instant startedAt) {
        return RunSummaryDto.builder()
                .runId(runId).source(runId.split(":")[0]).name(runId)
                .status(RunStatus.RUNNING).projectId("proj").startedAt(startedAt).build();
    }

    private static RunSource source(String id, RunSummaryDto row) {
        return new RunSource() {
            @Override public String sourceId() { return id; }
            @Override public List<RunSummaryDto> list(String t, String p, int l) { return List.of(row); }
            @Override public Optional<RunDetailDto> get(String t, String p, String nativeId) {
                return Optional.of(RunDetailDto.builder().summary(row).build());
            }
        };
    }
}
