package de.mhus.vance.brain.arthur;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for
 * {@link ArthurEngine#shouldAutoWaitOnDelegationPointerBlocked}. The
 * predicate decides whether a drained inbox should bypass the LLM
 * and yield the turn — see the method Javadoc and the
 * Slart-parking-on-Hactar background for the broader rationale.
 */
class ArthurAutoWaitTest {

    private static final String WORKER = "child-1";
    private static final String OTHER = "child-2";

    @Test
    void blockedFromDelegatedWorker_autoWaits() {
        List<SteerMessage> inbox = List.of(
                blocked(WORKER));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isTrue();
    }

    @Test
    void multipleBlockedFromSameDelegatedWorker_stillAutoWaits() {
        List<SteerMessage> inbox = List.of(
                blocked(WORKER),
                blocked(WORKER));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isTrue();
    }

    @Test
    void doneEvent_fallsThroughToLlm() {
        // DONE means RELAY is the right answer — must not short-circuit.
        List<SteerMessage> inbox = List.of(
                event(WORKER, ProcessEventType.DONE));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    @Test
    void blockedPlusDone_fallsThroughToLlm() {
        // Mixed inbox: a child that parked once and then completed.
        // The DONE is what the LLM should react to.
        List<SteerMessage> inbox = List.of(
                blocked(WORKER),
                event(WORKER, ProcessEventType.DONE));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    @Test
    void blockedFromForeignWorker_fallsThroughToLlm() {
        // Foreign worker (not our delegationPointer) — only the LLM
        // can decide what to do with that.
        List<SteerMessage> inbox = List.of(
                blocked(OTHER));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    @Test
    void userInputInDrain_fallsThroughToLlm() {
        List<SteerMessage> inbox = List.of(
                blocked(WORKER),
                userChat("hello"));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    @Test
    void emptyInbox_returnsFalse() {
        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, List.of()))
                .isFalse();
    }

    @Test
    void nullDelegationPointer_returnsFalse() {
        List<SteerMessage> inbox = List.of(blocked(WORKER));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(null, inbox))
                .isFalse();
    }

    @Test
    void blankDelegationPointer_returnsFalse() {
        List<SteerMessage> inbox = List.of(blocked(WORKER));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked("   ", inbox))
                .isFalse();
    }

    @Test
    void nullInbox_returnsFalse() {
        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, null))
                .isFalse();
    }

    @Test
    void failedFromDelegatedWorker_fallsThroughToLlm() {
        // FAILED needs LLM attention to react (apologise, retry, etc.).
        List<SteerMessage> inbox = List.of(
                event(WORKER, ProcessEventType.FAILED));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    @Test
    void stoppedFromDelegatedWorker_fallsThroughToLlm() {
        List<SteerMessage> inbox = List.of(
                event(WORKER, ProcessEventType.STOPPED));

        assertThat(ArthurEngine.shouldAutoWaitOnDelegationPointerBlocked(WORKER, inbox))
                .isFalse();
    }

    // ──────────────────── Helpers ────────────────────

    private static SteerMessage.ProcessEvent blocked(String sourceProcessId) {
        return event(sourceProcessId, ProcessEventType.BLOCKED);
    }

    private static SteerMessage.ProcessEvent event(String sourceProcessId, ProcessEventType type) {
        return new SteerMessage.ProcessEvent(
                Instant.now(),
                /*idempotencyKey*/ null,
                sourceProcessId,
                type,
                /*humanSummary*/ null,
                /*payload*/ null,
                /*eventId*/ "ev-" + sourceProcessId + "-" + type.name().toLowerCase(),
                /*inResponseToAt*/ null);
    }

    private static SteerMessage.UserChatInput userChat(String content) {
        return new SteerMessage.UserChatInput(
                Instant.now(), /*idempotencyKey*/ null,
                /*fromUser*/ "user-1", content);
    }
}
