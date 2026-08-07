package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScratchpadPromptBlockTest {

    private static MemoryDocument slot(String title, String content) {
        return MemoryDocument.builder()
                .tenantId("acme")
                .thinkProcessId("proc-1")
                .kind(MemoryKind.SCRATCHPAD)
                .title(title)
                .content(content)
                .build();
    }

    @Test
    void render_noSlots_staysSilent() {
        assertThat(ScratchpadPromptBlock.render(List.of())).isEmpty();
    }

    @Test
    void render_shortSlot_inlinesContentVerbatim() {
        String body = ScratchpadPromptBlock.render(List.of(slot("todo", "rebuild brain")));

        assertThat(body).startsWith("## Scratchpad");
        assertThat(body).contains("- `todo`: rebuild brain\n");
        assertThat(body).contains("`scratchpad_get(title)`");
    }

    @Test
    void render_longSlot_reducesToSizeHint() {
        String content = "x".repeat(ScratchpadPromptBlock.INLINE_MAX_CHARS + 1);

        String body = ScratchpadPromptBlock.render(List.of(slot("findings", content)));

        assertThat(body).contains("- `findings` — " + content.length()
                + " chars, read with `scratchpad_get('findings')`");
        assertThat(body).doesNotContain(content);
    }

    @Test
    void render_multiLineSlot_isNotInlinedEvenWhenShort() {
        String body = ScratchpadPromptBlock.render(List.of(slot("plan", "step 1\nstep 2")));

        assertThat(body).contains("read with `scratchpad_get('plan')`");
        assertThat(body).doesNotContain("step 1");
    }

    @Test
    void render_emptyContent_isMarkedRatherThanLookingLikeAMissingSlot() {
        String body = ScratchpadPromptBlock.render(List.of(slot("note", "")));

        assertThat(body).contains("- `note`: (empty)");
    }

    @Test
    void render_blankTitle_isSkipped() {
        String body = ScratchpadPromptBlock.render(
                List.of(slot(" ", "orphan"), slot("todo", "keep me")));

        assertThat(body).contains("- `todo`: keep me");
        assertThat(body).doesNotContain("orphan");
    }

    @Test
    void render_duplicateTitle_showsWhatScratchpadGetWouldReturn() {
        // Two active entries for one title can only come from a write-write
        // race; ScratchpadService.active() takes the first of the same
        // ordering, so the block must not show the second one.
        String body = ScratchpadPromptBlock.render(
                List.of(slot("todo", "first"), slot("todo", "second")));

        assertThat(body).contains("- `todo`: first");
        assertThat(body).doesNotContain("second");
    }

    @Test
    void render_manySlots_staysWithinTotalCapAndCountsTheRest() {
        // Each slot inlines ~120 chars, so the budget runs out well before
        // the last one.
        List<MemoryDocument> slots = IntStream.range(0, 40)
                .mapToObj(i -> slot("slot-" + i, "c".repeat(120)))
                .toList();

        String body = ScratchpadPromptBlock.render(slots);

        assertThat(body.length()).isLessThanOrEqualTo(ScratchpadPromptBlock.TOTAL_MAX_CHARS);
        assertThat(body).contains("further slot(s) not shown — `scratchpad_list()`");
        assertThat(body).contains("- `slot-0`");
    }

    @Test
    void render_budgetRunsOut_reducesLaterSlotsInsteadOfHidingThem() {
        // Ten inlinable slots don't all fit inlined. The early ones keep
        // their content, the later ones degrade to a size hint — reducing
        // beats hiding.
        String content = "a".repeat(ScratchpadPromptBlock.INLINE_MAX_CHARS);
        List<MemoryDocument> slots = IntStream.range(0, 10)
                .mapToObj(i -> slot("slot-" + i, content))
                .toList();

        String body = ScratchpadPromptBlock.render(slots);

        assertThat(body).contains("- `slot-0`: " + content);
        assertThat(body).contains("chars, read with `scratchpad_get(");
        assertThat(body.length()).isLessThanOrEqualTo(ScratchpadPromptBlock.TOTAL_MAX_CHARS);
    }
}
