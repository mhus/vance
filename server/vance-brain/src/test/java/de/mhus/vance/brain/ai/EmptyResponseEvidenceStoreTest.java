package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmptyResponseEvidenceStore}: label matching, the
 * entry bound, the retention window and the drain-removes semantics that
 * keep one capture attached to at most one report.
 */
class EmptyResponseEvidenceStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    private static EmptyResponseEvidence capture(Instant capturedAt, String modelName) {
        return new EmptyResponseEvidence(
                capturedAt,
                EmptyResponseEvidence.Kind.STREAM,
                "https://gateway.example/v1",
                modelName,
                200,
                List.of(),
                List.of("[DONE]"),
                false);
    }

    private static EmptyResponseEvidenceStore store() {
        return new EmptyResponseEvidenceStore(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void drainRecent_matchesOnModelName_andRemovesMatched() {
        EmptyResponseEvidenceStore store = store();
        store.register(capture(NOW, "m-1"));
        store.register(capture(NOW, "m-2"));

        List<EmptyResponseEvidence> drained = store.drainRecent("openai:m-1");

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).modelName()).isEqualTo("m-1");
        // Drained, not peeked: the capture cannot attach to a second report.
        assertThat(store.drainRecent("openai:m-1")).isEmpty();
        // The other model's capture is untouched and waits for its own report.
        assertThat(store.drainRecent("openai:m-2")).hasSize(1);
    }

    @Test
    void labelMatching_ignoresTheInstancePrefix() {
        EmptyResponseEvidenceStore store = store();
        store.register(capture(NOW, "m-1"));

        // Chain-entry labels are "instance:model" — a non-default provider
        // instance must not stop the capture from matching its report.
        assertThat(store.drainRecent("private-gateway:m-1")).hasSize(1);
    }

    @Test
    void lapsedCaptures_expireAndVanish() {
        EmptyResponseEvidenceStore store = store();
        Instant stale = NOW.minus(EmptyResponseEvidenceStore.MAX_AGE).minusSeconds(1);
        store.register(capture(stale, "m-1"));

        assertThat(store.drainRecent("openai:m-1")).isEmpty();
    }

    @Test
    void entryCount_isBounded_oldestEvictedFirst() {
        EmptyResponseEvidenceStore store = store();
        for (int i = 0; i < EmptyResponseEvidenceStore.MAX_ENTRIES + 4; i++) {
            store.register(capture(NOW, "m-" + i));
        }

        // The newest MAX_ENTRIES models survive; the earliest four are gone.
        for (int i = 0; i < 4; i++) {
            assertThat(store.drainRecent("openai:m-" + i)).isEmpty();
        }
        for (int i = 4; i < EmptyResponseEvidenceStore.MAX_ENTRIES + 4; i++) {
            assertThat(store.drainRecent("openai:m-" + i)).hasSize(1);
        }
    }

    @Test
    void retentionWindow_coversRetryBackoffChains() {
        // A 3-attempt empty budget with exponential backoff spans ~30s of
        // captures before the sink fires — the window must dwarf that.
        assertThat(EmptyResponseEvidenceStore.MAX_AGE).isGreaterThan(Duration.ofMinutes(5));
    }
}
