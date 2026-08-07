package de.mhus.vance.brain.prompt;

import de.mhus.vance.shared.memory.MemoryDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the {@code "## Scratchpad"} prompt block listing the slots the
 * engine wrote earlier in this process, so a note taken 30 turns ago is
 * still visible instead of having to be guessed at through
 * {@code scratchpad_get('<title>')}.
 *
 * <p><b>Empty inventory renders the empty string.</b> That is what lets
 * the block ride on every engine: the scratchpad tools are non-primary
 * but unrestricted (an engine with an empty {@code allowedTools()} may
 * call them), so a block rendered unconditionally would cost prompt
 * budget in every turn of every engine. Suppressed on empty, only a
 * process that actually took notes pays. Deliberately different from
 * {@code FrankieEngine.buildTodoListBlock}, which renders an invitation
 * (<i>"No active plan. Use todo_create(…)"</i>) when its list is empty —
 * a permanent invitation on every engine would push towards using the
 * scratchpad rather than enable it. Consequence: the block solves
 * <em>re-finding</em> notes, not <em>discovering</em> that slots exist —
 * the first write still comes from the manuals / {@code how_do_i} /
 * {@code find_tools}.
 *
 * <p>Rides as a
 * {@link de.mhus.vance.brain.ai.VanceSystemMessage#dynamic(String)
 * dynamic system message} like the sibling {@link PromptDateBlock} and
 * {@link PromptEnvironmentBlock}: slot content churns per turn, and the
 * Anthropic mapper places {@code cache_control} before dynamic blocks so
 * that churn does not invalidate the cached system+skills prefix (see
 * {@code specification/public/prompt-caching.md} §5a).
 *
 * <p>Bounded by construction — an injected block competes with the
 * conversation for context: short single-line slots are inlined
 * verbatim, everything else is reduced to a size hint, and the whole
 * block stays within {@link #TOTAL_MAX_CHARS}. Slots that no longer fit
 * are counted in a trailing line rather than silently dropped.
 *
 * <p>See {@code planning/scratchpad-review.md} §7.2 R1/R5.
 */
public final class ScratchpadPromptBlock {

    /**
     * Longest slot content still inlined verbatim. Longer content — and
     * any multi-line content, so an inlined slot is always exactly one
     * line — is reduced to a size hint.
     */
    static final int INLINE_MAX_CHARS = 200;

    /** Hard ceiling for the whole rendered block. */
    static final int TOTAL_MAX_CHARS = 2_000;

    /** Head-room kept free so the "not shown" line always fits. */
    private static final int FOOTER_RESERVE = 90;

    private static final String HEADER = """
            ## Scratchpad

            Notes you took earlier in this process — they survive history \
            compaction. Read the full text of a slot with \
            `scratchpad_get(title)`, overwrite it with \
            `scratchpad_set(title, content)`, drop it with \
            `scratchpad_delete(title)`.

            """;

    private ScratchpadPromptBlock() {}

    /**
     * Renders the block for the process's active scratchpad slots, or the
     * empty string when there are none — the caller short-circuits the
     * {@code dynamic} wrap on blank.
     *
     * @param slots active {@code SCRATCHPAD} entries of one process,
     *              oldest first (as
     *              {@link de.mhus.vance.shared.memory.MemoryService#activeByProcessAndKind}
     *              returns them)
     */
    public static String render(List<MemoryDocument> slots) {
        Map<String, String> bySlot = collect(slots);
        if (bySlot.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(HEADER);
        int budget = TOTAL_MAX_CHARS - FOOTER_RESERVE;
        int notShown = 0;
        for (Map.Entry<String, String> slot : bySlot.entrySet()) {
            String line = inlinable(slot.getValue())
                    ? inlineLine(slot.getKey(), slot.getValue())
                    : hintLine(slot.getKey(), slot.getValue());
            if (b.length() + line.length() > budget) {
                // Retry as a hint — a long inlined slot may still fit
                // reduced, and reducing beats hiding it.
                String hint = hintLine(slot.getKey(), slot.getValue());
                if (b.length() + hint.length() > budget) {
                    notShown++;
                    continue;
                }
                line = hint;
            }
            b.append(line);
        }
        if (notShown > 0) {
            b.append("- … ").append(notShown)
                    .append(" further slot(s) not shown — `scratchpad_list()`\n");
        }
        return b.toString();
    }

    /**
     * Title → content, first occurrence winning, blank titles skipped.
     *
     * <p>First-wins mirrors {@code ScratchpadService.active}, which takes
     * {@code hits.get(0)} of the same ordering: should a write-write race
     * ever leave two active entries for one title, the block shows the
     * value {@code scratchpad_get} would return rather than a second
     * opinion.
     */
    private static Map<String, String> collect(List<MemoryDocument> slots) {
        Map<String, String> bySlot = new LinkedHashMap<>();
        for (MemoryDocument slot : slots) {
            String title = slot.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            bySlot.putIfAbsent(title, slot.getContent() == null ? "" : slot.getContent());
        }
        return bySlot;
    }

    private static boolean inlinable(String content) {
        return content.length() <= INLINE_MAX_CHARS && content.indexOf('\n') < 0;
    }

    private static String inlineLine(String title, String content) {
        if (content.isBlank()) {
            return "- `" + title + "`: (empty)\n";
        }
        return "- `" + title + "`: " + content + "\n";
    }

    private static String hintLine(String title, String content) {
        return "- `" + title + "` — " + content.length()
                + " chars, read with `scratchpad_get('" + title + "')`\n";
    }
}
