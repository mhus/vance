package de.mhus.vance.brain.arthur;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArthurEngine#collapseBySourceProcessId(Map)}.
 *
 * <p>A worker that emits both an {@code emitReply} and a lifecycle
 * {@code DONE} produces two drain entries with the same
 * {@code sourceProcessId} — both carrying the same answer text.
 * The collapse step keeps the semantically richer entry (the
 * synthesised {@code BLOCKED} from the Reply) so Tier 2 of
 * {@code resolveRelayEvent} can auto-pick.
 */
class ArthurEngineRelayCollapseTest {

    private static final String WORKER_A = "proc-A";
    private static final String WORKER_B = "proc-B";

    @Test
    void emptyInput_returnsEmpty() {
        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(new LinkedHashMap<>());
        assertThat(result).isEmpty();
    }

    @Test
    void singleEvent_passesThrough() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.DONE, "done", at(1)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1).containsKey("ev1");
    }

    @Test
    void blockedReplyAndDoneFromSameWorker_collapsesToBlocked() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        SteerMessage.ProcessEvent reply =
                event(WORKER_A, ProcessEventType.BLOCKED, "the joke", at(1));
        SteerMessage.ProcessEvent done =
                event(WORKER_A, ProcessEventType.DONE, "done + last reply", at(2));
        in.put("ev1", reply);
        in.put("ev2", done);

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1);
        SteerMessage.ProcessEvent kept = result.values().iterator().next();
        assertThat(kept.type()).isEqualTo(ProcessEventType.BLOCKED);
        assertThat(kept.humanSummary()).isEqualTo("the joke");
    }

    @Test
    void doneAndBlockedFromSameWorker_collapsesToBlocked_orderIndependent() {
        // Same scenario but the DONE arrived first in the drain —
        // priority must still win over arrival order.
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.DONE, "done first", at(1)));
        in.put("ev2", event(WORKER_A, ProcessEventType.BLOCKED, "reply second", at(2)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().type())
                .isEqualTo(ProcessEventType.BLOCKED);
    }

    @Test
    void twoEventsFromDifferentWorkers_keepsBoth() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.BLOCKED, "A says", at(1)));
        in.put("ev2", event(WORKER_B, ProcessEventType.BLOCKED, "B says", at(2)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        // Two distinct workers — cannot collapse, LLM must pick eventRef.
        assertThat(result).hasSize(2);
    }

    @Test
    void twoBlockedEventsFromSameWorker_keepsLater() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.BLOCKED, "first reply", at(1)));
        in.put("ev2", event(WORKER_A, ProcessEventType.BLOCKED, "second reply", at(2)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().humanSummary())
                .isEqualTo("second reply");
    }

    @Test
    void doneBeatsFailed_sameWorker() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.FAILED, "failed", at(1)));
        in.put("ev2", event(WORKER_A, ProcessEventType.DONE, "done", at(2)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().type())
                .isEqualTo(ProcessEventType.DONE);
    }

    @Test
    void blockedBeatsSummary_sameWorker() {
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.SUMMARY, "summary", at(1)));
        in.put("ev2", event(WORKER_A, ProcessEventType.BLOCKED, "actual reply", at(2)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next().type())
                .isEqualTo(ProcessEventType.BLOCKED);
    }

    @Test
    void mixedWorkersAndDuplicates_collapsesPerWorker() {
        // Worker A: BLOCKED reply + DONE
        // Worker B: DONE only
        Map<String, SteerMessage.ProcessEvent> in = new LinkedHashMap<>();
        in.put("ev1", event(WORKER_A, ProcessEventType.BLOCKED, "A reply", at(1)));
        in.put("ev2", event(WORKER_A, ProcessEventType.DONE, "A done", at(2)));
        in.put("ev3", event(WORKER_B, ProcessEventType.DONE, "B done", at(3)));

        Map<String, SteerMessage.ProcessEvent> result =
                ArthurEngine.collapseBySourceProcessId(in);

        assertThat(result).hasSize(2);
        // A's representative is the BLOCKED-from-Reply event.
        SteerMessage.ProcessEvent a = result.values().stream()
                .filter(ev -> WORKER_A.equals(ev.sourceProcessId()))
                .findFirst()
                .orElseThrow();
        assertThat(a.type()).isEqualTo(ProcessEventType.BLOCKED);
        // B's representative is the only one it has.
        SteerMessage.ProcessEvent b = result.values().stream()
                .filter(ev -> WORKER_B.equals(ev.sourceProcessId()))
                .findFirst()
                .orElseThrow();
        assertThat(b.type()).isEqualTo(ProcessEventType.DONE);
    }

    // ────────────────────────── helpers ──────────────────────────

    private static SteerMessage.ProcessEvent event(
            String sourcePid, ProcessEventType type, String summary, Instant at) {
        return new SteerMessage.ProcessEvent(
                at, /*idempotencyKey*/ null, sourcePid, type,
                summary, /*payload*/ null,
                "evt-" + sourcePid + "-" + at.toEpochMilli(),
                /*inResponseToAt*/ null);
    }

    private static Instant at(long seconds) {
        return Instant.ofEpochSecond(1_700_000_000L + seconds);
    }
}
