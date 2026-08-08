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
 * <p><b>Slot text is untrusted.</b> An engine may well park an excerpt of
 * a fetched page or a read document in a slot — content that arrived
 * correctly wrapped as a user-role tool result. Re-emitting it here would
 * promote it to unwrapped <em>system</em> text for the rest of the
 * process, across the exact boundary {@link UntrustedContent} exists to
 * defend. The slot list therefore rides inside an
 * {@link UntrustedContent#wrap wrapped} block, titles are whitespace-
 * collapsed so they cannot open a heading at a line start, and content
 * carrying any line terminator is never inlined.
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

    /** Wrapper tag for the untrusted slot list. */
    private static final String TAG = "scratchpad-notes";

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
        StringBuilder body = new StringBuilder();
        int budget = TOTAL_MAX_CHARS - HEADER.length() - wrapOverhead() - FOOTER_RESERVE;
        int notShown = 0;
        for (Map.Entry<String, String> slot : bySlot.entrySet()) {
            String line = inlinable(slot.getValue())
                    ? inlineLine(slot.getKey(), slot.getValue())
                    : hintLine(slot.getKey(), slot.getValue());
            if (body.length() + line.length() > budget) {
                // Retry as a hint — a long inlined slot may still fit
                // reduced, and reducing beats hiding it.
                String hint = hintLine(slot.getKey(), slot.getValue());
                if (body.length() + hint.length() > budget) {
                    notShown++;
                    continue;
                }
                line = hint;
            }
            body.append(line);
        }
        if (notShown > 0) {
            body.append("- … ").append(notShown)
                    .append(" further slot(s) not shown — `scratchpad_list()`\n");
        }
        // Parts were neutralized per line so the budget was measured on the
        // final text; wrap's own neutralize pass is idempotent and adds
        // nothing further.
        return HEADER + UntrustedContent.wrap(TAG, body.toString().stripTrailing());
    }

    /**
     * Title → content, first occurrence winning, blank titles skipped.
     *
     * <p>First-wins mirrors {@code ScratchpadService.active}, which takes
     * {@code hits.get(0)} of the same ordering: should a write-write race
     * ever leave two active entries for one title, the block shows the
     * value {@code scratchpad_get} would return rather than a second
     * opinion.
     *
     * <p>Titles are whitespace-collapsed and delimiter-neutralized for
     * display: a slot title is model-chosen and only checked for blankness
     * on write, so an embedded newline would otherwise open a heading or
     * list item at a line start inside a system block. Two titles that
     * collapse onto the same key are deduplicated like a repeat — showing
     * one line twice would be the worse answer.
     */
    private static Map<String, String> collect(List<MemoryDocument> slots) {
        Map<String, String> bySlot = new LinkedHashMap<>();
        for (MemoryDocument slot : slots) {
            String title = UntrustedContent.collapseWhitespace(slot.getTitle());
            if (title.isEmpty()) {
                continue;
            }
            String content = slot.getContent() == null ? "" : slot.getContent();
            bySlot.putIfAbsent(
                    UntrustedContent.neutralize(title, TAG),
                    UntrustedContent.neutralize(content, TAG));
        }
        return bySlot;
    }

    private static boolean inlinable(String content) {
        return content.length() <= INLINE_MAX_CHARS && !hasLineBreak(content);
    }

    /**
     * Any Unicode line terminator, not just {@code \n} — a lone {@code \r}
     * or a {@code U+2028} still starts a new line in the rendered prompt,
     * which is what the one-line inline form must not allow.
     */
    private static boolean hasLineBreak(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\f'
                    || c == '\u000B' || c == '\u0085'
                    || c == '\u2028' || c == '\u2029') {
                return true;
            }
        }
        return false;
    }

    /** Fixed cost of the {@link UntrustedContent#wrap} envelope. */
    private static int wrapOverhead() {
        return UntrustedContent.wrap(TAG, "").length();
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
