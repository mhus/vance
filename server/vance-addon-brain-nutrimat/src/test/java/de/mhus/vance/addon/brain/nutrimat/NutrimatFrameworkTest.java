package de.mhus.vance.addon.brain.nutrimat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the Nutrimat framework layer: the engine naming
 * ({@code nutrimat-<nature>}) and the closed worker surface every nature
 * exposes.
 *
 * <p>Metadata-only — the engine is never started, so all constructor
 * dependencies are {@code null}. The constructor call is positional on
 * purpose: a signature change must break the compile here, not silently
 * shift behaviour.
 */
class NutrimatFrameworkTest {

    /** Minimal valid nature for the framework-level assertions. */
    private static class GoodNature extends AbstractNutrimat {
        GoodNature() {
            super(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        @Override
        protected String natureId() {
            return "janx";
        }

        @Override
        protected String loopType() {
            return "test loop";
        }
    }

    /** A nature whose id cannot be split out of {@code nutrimat-<nature>}. */
    private static class BadNature extends AbstractNutrimat {
        BadNature() {
            super(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        @Override
        protected String natureId() {
            return "bad-id";
        }

        @Override
        protected String loopType() {
            return "test loop";
        }
    }

    @Test
    void name_isDerivedFromNatureId() {
        assertThat(new GoodNature().name()).isEqualTo("nutrimat-janx");
    }

    @Test
    void name_rejectsUnsplittableNatureId() {
        // A dash inside the id would make 'nutrimat-<nature>' ambiguous and
        // the id lands in recipe names — the same reasoning as Trillian's
        // registry check. The registry indexes every bean by name() at boot,
        // so an unusable id still fails at startup.
        AbstractNutrimat engine = new BadNature();
        assertThatThrownBy(engine::name).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowedTools_engineBaselineMatchesFordWorkerSurface() {
        var set = new GoodNature().allowedTools();
        assertThat(set).isNotEmpty();
        // Discovery + intro essentials
        assertThat(set).contains("tool_list", "tool_description", "how_do_i", "manual_read", "tool_result_read");
        // Sub-worker spawn + user-facing signal
        assertThat(set).contains("process_spawn", "process_status", "vance_notify", "current_time", "whoami");
        // Read-side document operations (mutation stays per-recipe)
        assertThat(set)
                .contains("doc_read", "doc_read_lines", "doc_info", "doc_list", "doc_find", "doc_grep", "doc_link");
        // Research + memory + settings read
        assertThat(set).contains("web_fetch", "web_search", "research_search", "memory_search", "setting_get");
        // The work-target dispatch layer
        assertThat(set).contains("file_read", "file_write", "file_list", "exec_run");
    }

    @Test
    void exhaustedStopsUntilUserInput_defaultsToFordBehaviour() {
        // janx keeps the Ford baseline: a primary hard failure parks BLOCKED
        // and any pending message may wake it.
        assertThat(new GoodNature().exhaustedStopsUntilUserInput()).isFalse();
    }

    @Test
    void awaitingUserContinue_readsThePersistedStateFlag() {
        // The continue-gate marker lives in nutrimatState — tolerant read,
        // absent flag (or absent state) means "not parked".
        assertThat(AbstractNutrimat.awaitingUserContinue(new ThinkProcessDocument()))
                .isFalse();

        ThinkProcessDocument parked = new ThinkProcessDocument();
        parked.setEngineParams(java.util.Map.of("nutrimatState", java.util.Map.of("awaitingUserContinue", true)));
        assertThat(AbstractNutrimat.awaitingUserContinue(parked)).isTrue();

        ThinkProcessDocument past = new ThinkProcessDocument();
        past.setEngineParams(
                java.util.Map.of("nutrimatState", java.util.Map.of("awaitingUserContinue", false, "turns", 3)));
        assertThat(AbstractNutrimat.awaitingUserContinue(past)).isFalse();
    }

    @Test
    void iterationBudget_defaultsToUncapped() {
        // The base knows no budget — only a nature (redbull, clubmate) gives
        // its loop one; an uncapped loop runs until the natural stop and is
        // bounded by the wallclock net.
        assertThat(new GoodNature().iterationBudget(new ThinkProcessDocument())).isZero();
    }

    void workerContract_isFordShaped() {
        AbstractNutrimat engine = new GoodNature();
        // The closed outside: orchestrators steer synchronously and get one
        // reply per turn, exactly like a Ford worker.
        assertThat(engine.asyncSteer()).isFalse();
        assertThat(engine.planShaped()).isFalse();
        assertThat(engine.producesUserFacingOutput()).isTrue();
        assertThat(engine.allowsCrossProjectSpawn()).isFalse();
        assertThat(engine.roles()).isEmpty();
    }
}
