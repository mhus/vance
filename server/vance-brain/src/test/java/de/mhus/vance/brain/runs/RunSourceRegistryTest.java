package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.runs.RunAction;
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

        assertThat(registry.list(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", 10))
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

        assertThat(registry.list(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", 10)).hasSize(1);
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

        assertThat(registry.list(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", 2))
                .extracting(RunSummaryDto::getRunId)
                .containsExactly("process:b", "compose:c");
    }

    @Test
    void routesDetailToTheSourceNamedInTheId() {
        RunSourceRegistry registry = registryOf(
                source("workflow", summary("workflow:a", Instant.now())),
                source("process", summary("process:b", Instant.now())));

        assertThat(registry.get(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", "process:b")).isPresent();
        // Unknown source and malformed id are both "no such run" — the
        // caller learns nothing about which sources exist.
        assertThat(registry.get(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", "ghost:b")).isEmpty();
        assertThat(registry.get(de.mhus.vance.shared.permission.SecurityContext.SYSTEM, "acme", "proj", "nocolon")).isEmpty();
    }

    @Test
    void aSourceIdWithAColonIsRefusedAtStartup() {
        // It would make the composite id ambiguous, and the failure would
        // only show up later as a run that cannot be opened.
        assertThatThrownBy(() -> registryOf(source("we:ird", summary("x:y", Instant.now()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("we:ird");
    }

    // ──────────── visibleTo, on every route ────────────
    //
    // The read side filtered from the start; the action side did not, so a
    // run that GET /runs hid could still be stopped through
    // POST /runs/{id}/actions/stop — the effect landed and the answer was a
    // 404. These four pin the rule to the registry, where a source cannot
    // secure one route and forget another.

    @Test
    void listSkipsRunsTheSubjectMayNotSee() {
        RunSourceRegistry registry = registryOf(
                hidden("workflow", summary("workflow:a", Instant.now())),
                source("process", summary("process:b", Instant.now())));

        assertThat(registry.list(BOB, "acme", "proj", 10))
                .extracting(RunSummaryDto::getRunId)
                .containsExactly("process:b");
    }

    @Test
    void getReportsAHiddenRunAsAbsent() {
        RunSourceRegistry registry = registryOf(hidden("workflow", summary("workflow:a", Instant.now())));

        assertThat(registry.get(BOB, "acme", "proj", "workflow:a")).isEmpty();
    }

    @Test
    void allowedActionsOfAHiddenRunIsEmpty() {
        RecordingSource source = hidden("workflow", summary("workflow:a", Instant.now()));
        RunSourceRegistry registry = registryOf(source);

        assertThat(registry.allowedActions(BOB, "acme", "proj", "workflow:a")).isEmpty();
        assertThat(source.actionsAsked).isFalse();
    }

    @Test
    void performOnAHiddenRunIsRefusedAndHasNoEffect() {
        RecordingSource source = hidden("workflow", summary("workflow:a", Instant.now()));
        RunSourceRegistry registry = registryOf(source);

        assertThatThrownBy(() -> registry.perform(
                BOB, "acme", "proj", "workflow:a", RunAction.STOP, "because"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source.performed).isNull();
    }

    @Test
    void performOnAVisibleRunReachesTheSource() {
        RecordingSource source = new RecordingSource(
                "workflow", summary("workflow:a", Instant.now()), true);
        RunSourceRegistry registry = registryOf(source);

        registry.perform(BOB, "acme", "proj", "workflow:a", RunAction.STOP, "because");

        assertThat(source.performed).isEqualTo(RunAction.STOP);
    }

    private static final de.mhus.vance.shared.permission.SecurityContext BOB =
            de.mhus.vance.shared.permission.SecurityContext.user("bob", "acme", List.of());

    /** A source that answers {@code visibleTo} and remembers what it was asked. */
    private static final class RecordingSource implements RunSource {
        private final String id;
        private final RunSummaryDto row;
        private final boolean visible;
        private boolean actionsAsked;
        private @org.jspecify.annotations.Nullable RunAction performed;

        RecordingSource(String id, RunSummaryDto row, boolean visible) {
            this.id = id;
            this.row = row;
            this.visible = visible;
        }

        @Override public String sourceId() { return id; }

        @Override public List<RunSummaryDto> list(String t, String p, int l) { return List.of(row); }

        @Override public Optional<RunDetailDto> get(String t, String p, String nativeId) {
            return Optional.of(RunDetailDto.builder().summary(row).build());
        }

        @Override public java.util.Set<RunAction> allowedActions(
                String t, String p, String nativeId) {
            actionsAsked = true;
            return java.util.Set.of(RunAction.STOP);
        }

        @Override public void perform(
                String t, String p, String nativeId, RunAction action, String reason) {
            performed = action;
        }

        @Override public boolean visibleTo(
                de.mhus.vance.shared.permission.SecurityContext subject,
                String t, String p, String nativeId) {
            return visible;
        }
    }

    private static RecordingSource hidden(String id, RunSummaryDto row) {
        return new RecordingSource(id, row, false);
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
