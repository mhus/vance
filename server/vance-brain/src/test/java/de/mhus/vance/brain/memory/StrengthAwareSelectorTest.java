package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prak.PrakProperties;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.prak.SpanStrength;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The strength × mode compaction matrix — a destructive, irreversible
 * decision (a wrong branch folds a PINNED/STRONG message into a summary and
 * loses it). Verifies every cell of the §6.1 table plus anchor preservation,
 * the NONE short-circuit, and the optimistic-fallback for unrated messages.
 *
 * <p>Each cell is exercised by placing exactly one target message ahead of a
 * one-message anchor (all anchors set to 1), so a target appears in the
 * compaction set iff its cell says "compact".
 */
class StrengthAwareSelectorTest {

    private StrengthAwareSelector selector;

    @BeforeEach
    void setUp() {
        PrakProperties props = new PrakProperties();
        // Neutralise the anchor for cell tests — the target sits at index 0,
        // the single anchor message (last K=1) is always preserved.
        props.setSoftAnchor(1);
        props.setHardAnchor(1);
        props.setEmergencyAnchor(1);
        selector = new StrengthAwareSelector(props);
    }

    // ──────────────── 6×3 matrix ────────────────

    @Test
    void pinned_staysInEveryMode() {
        assertCompaction(strength(SpanStrength.PINNED), false, false, false);
    }

    @Test
    void strong_staysExceptEmergency() {
        assertCompaction(strength(SpanStrength.STRONG), false, false, true);
    }

    @Test
    void normal_compactsInHardAndEmergency() {
        assertCompaction(strength(SpanStrength.NORMAL), false, true, true);
    }

    @Test
    void weak_compactsInEveryMode() {
        assertCompaction(strength(SpanStrength.WEAK), true, true, true);
    }

    @Test
    void unratedTrivial_treatedAsWeak_compactsInEveryMode() {
        assertCompaction(unrated("ok"), true, true, true);
        assertCompaction(unrated("Let me check the config first"), true, true, true);
    }

    @Test
    void unratedSubstantive_staysInSoft_compactsInHardAndEmergency() {
        assertCompaction(
                unrated("The users table needs a composite index on (tenant, created)."),
                false, true, true);
    }

    /**
     * Drives one target message through all three active modes.
     * {@code expectSoft/Hard/Emergency} = whether the target is selected for
     * compaction in that mode.
     */
    private void assertCompaction(ChatMessageDocument target,
            boolean expectSoft, boolean expectHard, boolean expectEmergency) {
        assertThat(isSelected(target, CompactionMode.SOFT))
                .as("SOFT").isEqualTo(expectSoft);
        assertThat(isSelected(target, CompactionMode.HARD))
                .as("HARD").isEqualTo(expectHard);
        assertThat(isSelected(target, CompactionMode.EMERGENCY))
                .as("EMERGENCY").isEqualTo(expectEmergency);
    }

    private boolean isSelected(ChatMessageDocument target, CompactionMode mode) {
        ChatMessageDocument anchor = strength(SpanStrength.WEAK); // last K=1, always kept
        List<ChatMessageDocument> result =
                selector.selectForCompaction(List.of(target, anchor), mode);
        return result.contains(target) && !result.contains(anchor);
    }

    // ──────────────── anchor preservation ────────────────

    @Test
    void anchor_lastKMessages_alwaysStay_evenWhenWeak() {
        PrakProperties props = new PrakProperties();
        props.setHardAnchor(3);
        StrengthAwareSelector s = new StrengthAwareSelector(props);

        // Five WEAK messages: HARD would compact all — but the last 3 are anchor.
        List<ChatMessageDocument> active = new ArrayList<>();
        for (int i = 0; i < 5; i++) active.add(strength(SpanStrength.WEAK));

        List<ChatMessageDocument> result = s.selectForCompaction(active, CompactionMode.HARD);

        assertThat(result).containsExactly(active.get(0), active.get(1));
        assertThat(result).doesNotContain(active.get(2), active.get(3), active.get(4));
    }

    // ──────────────── edge cases ────────────────

    @Test
    void noneMode_selectsNothing() {
        List<ChatMessageDocument> active =
                List.of(strength(SpanStrength.WEAK), strength(SpanStrength.WEAK));
        assertThat(selector.selectForCompaction(active, CompactionMode.NONE)).isEmpty();
    }

    @Test
    void emptyOrNullInput_selectsNothing() {
        assertThat(selector.selectForCompaction(List.of(), CompactionMode.HARD)).isEmpty();
        assertThat(selector.selectForCompaction(null, CompactionMode.HARD)).isEmpty();
    }

    @Test
    void historyUnderAnchor_selectsNothing() {
        PrakProperties props = new PrakProperties();
        props.setHardAnchor(5);
        StrengthAwareSelector s = new StrengthAwareSelector(props);

        List<ChatMessageDocument> active =
                List.of(strength(SpanStrength.WEAK), strength(SpanStrength.WEAK));
        assertThat(s.selectForCompaction(active, CompactionMode.HARD)).isEmpty();
    }

    @Test
    void output_preservesOrderAndReferences() {
        PrakProperties props = new PrakProperties();
        props.setHardAnchor(1);
        StrengthAwareSelector s = new StrengthAwareSelector(props);

        ChatMessageDocument a = strength(SpanStrength.WEAK);
        ChatMessageDocument b = strength(SpanStrength.WEAK);
        ChatMessageDocument c = strength(SpanStrength.WEAK);
        List<ChatMessageDocument> result =
                s.selectForCompaction(List.of(a, b, c), CompactionMode.HARD);

        // Same references, chronological order, anchor (c) excluded.
        assertThat(result).containsExactly(a, b);
    }

    // ──────────────── helpers ────────────────

    // ChatMessageDocument is @Data (value equality), so identical payloads
    // would compare equal and break contains()/containsExactly() identity —
    // give every message a unique body.
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static ChatMessageDocument strength(SpanStrength s) {
        return ChatMessageDocument.builder()
                .content("payload-" + SEQ.incrementAndGet())
                .tags(Set.of(s.tag()))
                .build();
    }

    private static ChatMessageDocument unrated(@Nullable String content) {
        return ChatMessageDocument.builder()
                .content(content == null ? "" : content)
                .tags(Set.of())
                .build();
    }
}
