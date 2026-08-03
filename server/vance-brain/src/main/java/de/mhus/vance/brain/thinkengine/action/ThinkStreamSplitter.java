package de.mhus.vance.brain.thinkengine.action;

import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;

/**
 * Splits an inline reasoning stream ({@code <think>…</think>} markup, as
 * emitted by Qwen3 / DeepSeek-R1 / Granite-Reasoning) into two channels
 * as deltas arrive: the reasoning inside the tags goes to a "thinking"
 * consumer, everything else to an "answer" consumer.
 *
 * <p>Without this, the whole raw stream — {@code <think>} tags and all —
 * lands in the live answer bubble, then the committed message re-shows
 * the same reasoning in the "thoughts" side-channel: the user sees it
 * twice. Routing the tag content to the thinking channel keeps the live
 * answer clean and unifies reasoning on one channel (mirroring how
 * separate-field models deliver it via {@code onPartialThinking}).
 *
 * <p>Stateful and streaming-safe: tags may be split across deltas, so a
 * trailing fragment that could be the start of a tag is held back until
 * the next delta (or {@link #flush} at end-of-stream) disambiguates it.
 *
 * <p>No-op passthrough when the stream contains no {@code <think>} tag —
 * separate-field models (GLM, Anthropic, Gemini) and non-reasoning
 * models stream straight through to the answer consumer unchanged.
 *
 * <p>Not thread-safe; a stream is consumed by one handler thread.
 */
@NullMarked
public final class ThinkStreamSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder buf = new StringBuilder();
    private boolean insideThink = false;

    /** Feed a delta; routes decoded text to {@code answer}/{@code think}. */
    public void accept(String delta, Consumer<String> answer, Consumer<String> think) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        buf.append(delta);
        process(answer, think, false);
    }

    /**
     * End-of-stream: emit everything still buffered, including any
     * held-back partial-tag fragment (it turned out not to be a tag) and
     * an unterminated {@code <think>} block (routed to {@code think}).
     */
    public void flush(Consumer<String> answer, Consumer<String> think) {
        process(answer, think, true);
    }

    private void process(Consumer<String> answer, Consumer<String> think, boolean end) {
        while (true) {
            if (!insideThink) {
                int open = buf.indexOf(OPEN);
                if (open >= 0) {
                    if (open > 0) {
                        answer.accept(buf.substring(0, open));
                    }
                    buf.delete(0, open + OPEN.length());
                    insideThink = true;
                    continue;
                }
                emitSafe(answer, OPEN, end);
                return;
            }
            int close = buf.indexOf(CLOSE);
            if (close >= 0) {
                if (close > 0) {
                    think.accept(buf.substring(0, close));
                }
                buf.delete(0, close + CLOSE.length());
                insideThink = false;
                continue;
            }
            emitSafe(think, CLOSE, end);
            return;
        }
    }

    /**
     * Emit the buffer to {@code out}, holding back a trailing fragment
     * that is a proper prefix of {@code tag} (it might complete into that
     * tag on the next delta). At {@code end} nothing is held back.
     */
    private void emitSafe(Consumer<String> out, String tag, boolean end) {
        if (buf.length() == 0) {
            return;
        }
        if (end) {
            out.accept(buf.toString());
            buf.setLength(0);
            return;
        }
        int hold = trailingTagPrefixLen(tag);
        int emit = buf.length() - hold;
        if (emit > 0) {
            out.accept(buf.substring(0, emit));
            buf.delete(0, emit);
        }
    }

    /**
     * Longest k in [0, tag.length()) such that the buffer ends with
     * {@code tag.substring(0, k)} — the fragment to hold back so a tag
     * split across deltas is not emitted as content.
     */
    private int trailingTagPrefixLen(String tag) {
        int max = Math.min(buf.length(), tag.length() - 1);
        for (int k = max; k > 0; k--) {
            boolean match = true;
            int start = buf.length() - k;
            for (int i = 0; i < k; i++) {
                if (buf.charAt(start + i) != tag.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return k;
            }
        }
        return 0;
    }
}
