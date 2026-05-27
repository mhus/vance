package de.mhus.vance.brain.prak;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Shared regex patterns for detecting "no substance" chat turns —
 * short acknowledgements and assistant self-narration. Used by both
 * {@link CheapPathFilter} (skip-or-analyse gate) and
 * {@link SpanStrengthDeriver} (trivial-pattern downgrade to
 * {@code STRENGTH:weak}). See {@code planning/memory-evaluation-pipeline.md}
 * §4b.2.
 *
 * <p>Stateless and thread-safe; the compiled patterns are reused.
 */
final class TrivialPatterns {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    static final Pattern ACK = Pattern.compile(
            "^\\s*(ok|okay|ja|nein|danke|gut|alles klar|verstanden|"
                    + "yes|no|sure|got it|thanks|thank you|cool|nice|"
                    + "👍|👌|✅)\\s*[.!]*\\s*$",
            FLAGS);

    static final Pattern SELF_NARRATION = Pattern.compile(
            "^\\s*(ich werde (jetzt|nun|gleich|als nächstes)|"
                    + "ich lese|ich öffne|ich schaue mir|lass mich|"
                    + "let me|i('| wi)ll (now|just|first)|i'm going to|"
                    + "i am going to|i will now)\\b",
            FLAGS);

    private TrivialPatterns() {
        // Utility — not instantiable.
    }

    /** Short acknowledgement ("ok", "ja", "thanks", "👍") with no further content. */
    static boolean isAck(String text) {
        if (StringUtils.isBlank(text)) return false;
        return ACK.matcher(text).matches();
    }

    /** Assistant self-narration ("Ich werde jetzt …", "Let me check …"). */
    static boolean isSelfNarration(String text) {
        if (StringUtils.isBlank(text)) return false;
        return SELF_NARRATION.matcher(text).find();
    }
}
