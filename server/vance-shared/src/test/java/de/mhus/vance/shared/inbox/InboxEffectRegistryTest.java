package de.mhus.vance.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.EffectFact;
import de.mhus.vance.api.inbox.MaximegalonType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The registry carries the security property of the whole mechanism: it
 * must run an effect only on an unambiguous human "yes", and refuse in
 * every other situation.
 */
class InboxEffectRegistryTest {

    private static final String TYPE = "permission-request";

    @Test
    void approvedAnswer_runsTheEffectOnce() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        boolean ran = registry.dispatch(approvalItem(TYPE), approved(true));

        assertThat(ran).isTrue();
        assertThat(effect.calls).containsExactly("approved");
    }

    @Test
    void rejectedAnswer_runsRejectNotApprove() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        registry.dispatch(approvalItem(TYPE), approved(false));

        assertThat(effect.calls).containsExactly("rejected");
    }

    @Test
    void itemWithoutEffectType_runsNothing() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        boolean ran = registry.dispatch(approvalItem(null), approved(true));

        assertThat(ran).isFalse();
        assertThat(effect.calls).isEmpty();
    }

    @Test
    void unknownEffectType_runsNothing() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        // An item can outlive the release that knew its effect — refusing
        // beats guessing.
        boolean ran = registry.dispatch(approvalItem("kit-install"), approved(true));

        assertThat(ran).isFalse();
        assertThat(effect.calls).isEmpty();
    }

    @Test
    void abstention_isNotConsent() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        for (AnswerOutcome outcome : List.of(
                AnswerOutcome.INSUFFICIENT_INFO, AnswerOutcome.UNDECIDABLE)) {
            AnswerPayload abstained = AnswerPayload.builder()
                    .outcome(outcome).reason("no idea").answeredBy("alice").build();

            assertThat(registry.dispatch(approvalItem(TYPE), abstained)).isFalse();
        }
        assertThat(effect.calls).isEmpty();
    }

    @Test
    void malformedApprovalFlag_readsAsNo() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        // Value missing entirely, wrong key, and a truthy-looking string:
        // none of these may pass as consent.
        registry.dispatch(approvalItem(TYPE), decided(null));
        registry.dispatch(approvalItem(TYPE), decided(Map.of("confirmed", true)));
        registry.dispatch(approvalItem(TYPE), decided(Map.of("approved", "true")));

        assertThat(effect.calls).containsExactly("rejected", "rejected", "rejected");
    }

    @Test
    void nonApprovalItemType_runsNothing() {
        RecordingEffect effect = new RecordingEffect(TYPE);
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(effect));

        MaximegalonDocument decision = approvalItem(TYPE);
        decision.setType(MaximegalonType.DECISION);

        // A DECISION answer carries no yes/no — approve/reject cannot be
        // derived from it, so nothing runs.
        assertThat(registry.dispatch(decision, approved(true))).isFalse();
        assertThat(effect.calls).isEmpty();
    }

    @Test
    void throwingEffect_surfacesAsEffectFailed() {
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(new InboxEffect() {
            @Override
            public String effectType() {
                return TYPE;
            }

            @Override
            public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
                throw new IllegalStateException("grant storage down");
            }

            @Override
            public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
            }
        }));

        assertThatThrownBy(() -> registry.dispatch(approvalItem(TYPE), approved(true)))
                .isInstanceOf(InboxEffectRegistry.InboxEffectFailedException.class)
                .hasRootCauseMessage("grant storage down");
    }

    @Test
    void describe_delegatesToTheEffect() {
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(new InboxEffect() {
            @Override
            public String effectType() {
                return TYPE;
            }

            @Override
            public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
            }

            @Override
            public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
            }

            @Override
            public java.util.Optional<EffectDescription> describe(MaximegalonDocument item) {
                return java.util.Optional.of(new EffectDescription(
                        "PENDING", null, List.of(new EffectFact("Scope", "PROJECT 'test1'"))));
            }
        }));

        assertThat(registry.describe(approvalItem(TYPE)))
                .get()
                .extracting(EffectDescription::status)
                .isEqualTo("PENDING");
    }

    @Test
    void describe_withoutEffectOrUnknownType_isEmpty() {
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(new RecordingEffect(TYPE)));

        assertThat(registry.describe(approvalItem(null))).isEmpty();
        assertThat(registry.describe(approvalItem("kit-install"))).isEmpty();
    }

    @Test
    void describe_thatThrows_doesNotBreakTheListing() {
        InboxEffectRegistry registry = new InboxEffectRegistry(List.of(new InboxEffect() {
            @Override
            public String effectType() {
                return TYPE;
            }

            @Override
            public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
            }

            @Override
            public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
            }

            @Override
            public java.util.Optional<EffectDescription> describe(MaximegalonDocument item) {
                throw new IllegalStateException("storage down");
            }
        }));

        // Describing is a read for display — a failure must not take the
        // inbox down with it.
        assertThat(registry.describe(approvalItem(TYPE))).isEmpty();
    }

    @Test
    void duplicateEffectType_failsAtStartup() {
        // Two beans claiming the same key would make dispatch order decide
        // what a "yes" does — that must never boot.
        assertThatThrownBy(() -> new InboxEffectRegistry(
                List.of(new RecordingEffect(TYPE), new RecordingEffect(TYPE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TYPE);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static MaximegalonDocument approvalItem(String effectType) {
        return MaximegalonDocument.builder()
                .id("item-1")
                .tenantId("acme")
                .type(MaximegalonType.APPROVAL)
                .effectType(effectType)
                .effectRef("req-1")
                .requiresAction(true)
                .build();
    }

    private static AnswerPayload approved(boolean approved) {
        return decided(Map.of("approved", approved));
    }

    private static AnswerPayload decided(Map<String, Object> value) {
        return AnswerPayload.builder()
                .outcome(AnswerOutcome.DECIDED)
                .value(value)
                .answeredBy("alice")
                .build();
    }

    private static final class RecordingEffect implements InboxEffect {
        private final String type;
        private final List<String> calls = new ArrayList<>();

        private RecordingEffect(String type) {
            this.type = type;
        }

        @Override
        public String effectType() {
            return type;
        }

        @Override
        public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
            calls.add("approved");
        }

        @Override
        public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
            calls.add("rejected");
        }
    }
}
