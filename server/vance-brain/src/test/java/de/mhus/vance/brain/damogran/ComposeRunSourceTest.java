package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.brain.runs.ComposeRunSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ComposeRunSource} from inside the damogran package: the
 * status transitions it has to observe ({@code complete}, {@code fail})
 * are package-private, because only the runner is meant to drive them.
 * Widening that for a test would be the wrong trade.
 */
class ComposeRunSourceTest {

    private final ComposeRunRegistry registry = new ComposeRunRegistry();
    private final ComposeRunSource source = new ComposeRunSource(registry);

    @Test
    void listsOnlyTheCallersProject() {
        registry.register(run("r1", "proj"));
        registry.register(run("r2", "other"));

        assertThat(source.list("acme", "proj", 10))
                .singleElement()
                .satisfies(r -> assertThat(r.getRunId()).isEqualTo("compose:r1"));
    }

    @Test
    void mapsTheLifecycleOntoTheSharedVocabulary() {
        ComposeRun running = run("r1", "proj");
        registry.register(running);
        assertThat(statusOf("r1")).isEqualTo(RunStatus.RUNNING);

        // Cancel requested but still going — that is the STOPPING window
        // the shared vocabulary has a word for.
        running.requestCancel();
        assertThat(statusOf("r1")).isEqualTo(RunStatus.STOPPING);

        ComposeRun ok = run("r2", "proj");
        registry.register(ok);
        ok.complete(new DamogranComposeResult(DamogranStatus.SUCCESS, "ws", List.of(), null));
        assertThat(statusOf("r2")).isEqualTo(RunStatus.DONE);

        ComposeRun bad = run("r3", "proj");
        registry.register(bad);
        bad.fail("boom");
        assertThat(statusOf("r3")).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void detailCarriesTheTasksAndSaysThatItIsTransient() {
        ComposeRun run = run("r1", "proj");
        registry.register(run);
        run.taskDone(DamogranTaskResult.success(List.of(), null));
        run.taskDone(DamogranTaskResult.failure("exit 1"));

        var detail = source.get("acme", "proj", "r1").orElseThrow();

        assertThat(detail.getSteps()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(detail.getSteps().get(0).getOutcome()).isEqualTo("success");
        assertThat(detail.getSteps().get(1).getOutcome()).isEqualTo("failure");
        assertThat(detail.getSteps().get(1).getDetail()).isEqualTo("exit 1");
        // The UI needs this to explain a run that will simply be gone.
        assertThat(detail.getExtra()).containsEntry("transient", true);
    }

    @Test
    void aRunOfAnotherProjectReadsAsAbsent() {
        registry.register(run("r1", "other"));

        assertThat(source.get("acme", "proj", "r1")).isEmpty();
    }

    private RunStatus statusOf(String runId) {
        return source.get("acme", "proj", runId).orElseThrow().getSummary().getStatus();
    }

    private static ComposeRun run(String runId, String projectId) {
        return new ComposeRun(runId, "acme", projectId, "ws-" + runId, Instant.now());
    }
}
