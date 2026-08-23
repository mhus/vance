package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.MaximegalonType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns something a person typed into the answer a waiting gate expects.
 *
 * <p>A gate always asks through the inbox, where the answer is structured
 * by the form. When the run is owned by a process sitting in a
 * conversation, the person will often just reply there instead — and that
 * reply is prose. This class is the only place that guesses what the prose
 * meant, and it is deliberately strict about it: an answer it cannot read
 * is <em>not</em> an answer, so the gate stays open and the question can be
 * asked again. Guessing wrong here would pass a gate nobody passed.
 *
 * <p>Pure and static — the same input always yields the same reading, which
 * is what makes it testable and what makes a surprising outcome traceable
 * to a word list rather than to a model.
 */
final class GateChatAnswerParser {

    private static final Set<String> AFFIRMATIVE = Set.of(
            "yes", "y", "ok", "okay", "approve", "approved", "go", "go ahead",
            "continue", "proceed", "do it", "sure", "confirm", "confirmed",
            "ja", "jo", "jup", "passt", "weiter", "mach", "los", "einverstanden");

    private static final Set<String> NEGATIVE = Set.of(
            "no", "n", "nope", "reject", "rejected", "deny", "stop", "cancel",
            "abort", "don't", "dont", "nein", "ne", "nö", "abbrechen", "stopp");

    private GateChatAnswerParser() {
    }

    /**
     * Read {@code text} as an answer to a gate of {@code type}.
     *
     * @param options the {@code options} of a DECISION gate; ignored otherwise
     * @param answeredBy user id recorded on the answer
     * @return the answer, or empty when the text cannot be read as one — in
     *         which case the caller must leave the gate open
     */
    static Optional<AnswerPayload> parse(
            MaximegalonType type,
            String text,
            List<String> options,
            String answeredBy) {
        if (text == null) return Optional.empty();
        String norm = text.trim();
        if (norm.isEmpty()) return Optional.empty();

        return switch (type) {
            case APPROVAL -> parseApproval(norm, answeredBy);
            case DECISION -> parseDecision(norm, options, answeredBy);
            // Free-form by definition: whatever was said is the answer.
            case FEEDBACK -> Optional.of(payload(Map.of("text", norm), answeredBy));
            default -> Optional.empty();
        };
    }

    private static Optional<AnswerPayload> parseApproval(String text, String answeredBy) {
        String key = normalise(text);
        if (AFFIRMATIVE.contains(key)) {
            return Optional.of(payload(Map.of("approved", true), answeredBy));
        }
        if (NEGATIVE.contains(key)) {
            return Optional.of(payload(Map.of("approved", false), answeredBy));
        }
        // Matched on the whole utterance only. "yes, but first check X" is not
        // a yes — it is a conversation, and the gate should stay open for it.
        return Optional.empty();
    }

    private static Optional<AnswerPayload> parseDecision(
            String text, List<String> options, String answeredBy) {
        if (options == null || options.isEmpty()) return Optional.empty();
        String key = normalise(text);
        for (String option : options) {
            if (option != null && normalise(option).equals(key)) {
                return Optional.of(payload(Map.of("chosen", option), answeredBy));
            }
        }
        return Optional.empty();
    }

    /** Lower-case, trimmed, without surrounding punctuation. */
    private static String normalise(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int from = 0;
        int to = s.length();
        while (from < to && isTrimmable(s.charAt(from))) from++;
        while (to > from && isTrimmable(s.charAt(to - 1))) to--;
        return s.substring(from, to);
    }

    private static boolean isTrimmable(char c) {
        return c == '.' || c == '!' || c == '?' || c == ',' || c == ';'
                || c == ':' || c == '"' || c == '\'' || Character.isWhitespace(c);
    }

    private static AnswerPayload payload(Map<String, Object> value, String answeredBy) {
        AnswerPayload p = new AnswerPayload();
        p.setOutcome(AnswerOutcome.DECIDED);
        p.setValue(new LinkedHashMap<>(value));
        p.setAnsweredBy(answeredBy == null ? "" : answeredBy);
        return p;
    }

    /** Options declared on a gate's inbox spec, for DECISION matching. */
    @SuppressWarnings("unchecked")
    static List<String> optionsOf(@Nullable Map<String, Object> payload) {
        if (payload == null) return List.of();
        Object raw = payload.get("options");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
