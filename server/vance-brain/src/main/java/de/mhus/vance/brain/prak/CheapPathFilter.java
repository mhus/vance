package de.mhus.vance.brain.prak;

import de.mhus.vance.api.chat.ChatRole;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Decides whether a span is worth handing to the LLM analyzer, and
 * produces an expected item-count range for the sanitizer's hard cap.
 *
 * <p>Pure Java pre-filter — no LLM. The aim is to skip ~80% of
 * spontaneous trigger checks without spending tokens (§4a.2). The
 * remaining ~20% still go to the analyzer; the filter is permissive
 * by design (false-positives = wasted analyzer call, false-negatives =
 * lost insight, the second is worse).
 *
 * <p>Skip reasons applied in order:
 * <ol>
 *   <li>{@code empty}: zero messages.</li>
 *   <li>{@code below-token-threshold}: span sums to fewer than ~50 tokens.</li>
 *   <li>{@code no-substance}: no markers AND no substantial user turn.</li>
 *   <li>{@code only-ack-or-narration}: every non-trivial message is
 *       either a short ack or assistant self-narration.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CheapPathFilter {

    /** Substantial-user-turn threshold: tokens per user turn. */
    static final int SUBSTANTIAL_USER_TOKENS = 30;

    /** Acks max length in tokens — anything longer is not an ack. */
    static final int ACK_MAX_TOKENS = 10;

    private static final Pattern ACK_PATTERN = Pattern.compile(
            "^\\s*(ok|okay|ja|nein|danke|gut|alles klar|verstanden|"
                    + "yes|no|sure|got it|thanks|thank you|cool|nice|"
                    + "👍|👌|✅)\\s*[.!]*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern SELF_NARRATION_PATTERN = Pattern.compile(
            "^\\s*(ich werde (jetzt|nun|gleich|als nächstes)|"
                    + "ich lese|ich öffne|ich schaue mir|lass mich|"
                    + "let me|i('| wi)ll (now|just|first)|i'm going to|"
                    + "i am going to|i will now)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final HotPathMarkerDetector markerDetector;

    public SpanProfile profile(List<SpanMessage> messages) {
        if (messages.isEmpty()) {
            return new SpanProfile(0, 0, 0, 0, 0, 0, "empty");
        }

        int approxTokens = 0;
        int substantialUserTurns = 0;
        int markerHits = 0;
        int trivialAcks = 0;
        int selfNarrations = 0;

        for (SpanMessage msg : messages) {
            String content = msg.content();
            int tokens = approxTokenCount(content);
            approxTokens += tokens;

            markerHits += markerDetector.detect(content).size();

            if (msg.role() == ChatRole.USER) {
                if (tokens > SUBSTANTIAL_USER_TOKENS) {
                    substantialUserTurns++;
                }
                if (tokens <= ACK_MAX_TOKENS && isTrivialAck(content)) {
                    trivialAcks++;
                }
            } else if (msg.role() == ChatRole.ASSISTANT) {
                if (isSelfNarration(content)) {
                    selfNarrations++;
                }
                if (tokens <= ACK_MAX_TOKENS && isTrivialAck(content)) {
                    trivialAcks++;
                }
            }
        }

        String skipReason = computeSkipReason(
                messages.size(), approxTokens, substantialUserTurns,
                markerHits, trivialAcks, selfNarrations);

        return new SpanProfile(
                messages.size(),
                approxTokens,
                substantialUserTurns,
                markerHits,
                trivialAcks,
                selfNarrations,
                skipReason);
    }

    private static @org.jspecify.annotations.Nullable String computeSkipReason(
            int messageCount, int approxTokens, int substantialUserTurns,
            int markerHits, int trivialAcks, int selfNarrations) {

        if (approxTokens < SpanProfile.MIN_TOKEN_COUNT) {
            return "below-token-threshold";
        }
        // Most specific reason first — every message identifiably ack or
        // self-narration is stronger evidence of "nothing to extract"
        // than the general no-substance case.
        if (markerHits == 0
                && (trivialAcks + selfNarrations) == messageCount) {
            return "only-ack-or-narration";
        }
        if (markerHits == 0 && substantialUserTurns == 0) {
            return "no-substance";
        }
        return null;
    }

    static int approxTokenCount(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        // Whitespace-split is rough but good enough — a real tokenizer
        // would be model-specific. Off by ~25% in either direction is
        // fine for skip-or-not decisions.
        int count = 0;
        for (String t : text.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    static boolean isTrivialAck(String text) {
        return ACK_PATTERN.matcher(text).matches();
    }

    static boolean isSelfNarration(String text) {
        return SELF_NARRATION_PATTERN.matcher(text).find();
    }
}
