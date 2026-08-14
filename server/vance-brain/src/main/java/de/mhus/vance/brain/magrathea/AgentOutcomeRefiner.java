package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.util.JsonReplyExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads an agent's answer as a <em>judgement</em> and turns it into the
 * outcome the plan branches on.
 *
 * <p>An {@code agent_task} normally ends in {@code success} and the plan
 * routes on that alone — fine when the step either happened or did not.
 * It is not fine when the step's whole purpose was to assess something:
 * "is this good enough", "which of these is it". There the answer <em>is</em>
 * the branch, and leaving the plan to pick it apart afterwards means
 * writing a condition that re-parses prose.
 *
 * <p>Two shapes, deliberately not one:
 * <ul>
 *   <li>{@code decide:} — a classification. The chosen token <em>is</em> the
 *       outcome, so {@code on:} routes it directly; no cases, no second
 *       vocabulary.</li>
 *   <li>{@code score:} — a graded judgement on the fixed scale
 *       {@code [0.0, 1.0]}, mapped to an outcome by declared bands. The
 *       scale is fixed on purpose: a threshold means the same thing in
 *       every plan, and "0.7" does not have to be looked up.</li>
 * </ul>
 *
 * <p>When the answer does not fit the shape it was asked for, the result is
 * {@link NeedsCorrection} rather than a failure. A model that returned
 * prose instead of a token has not failed the task — it has misread the
 * question, and asking again is cheaper and more honest than routing an
 * unparseable answer somewhere.
 *
 * <p>Pure: no IO, no state. Everything it needs is the spec and the text.
 */
final class AgentOutcomeRefiner {

    /** How many re-asks a step allows before the mis-shaped answer is an error. */
    static final int DEFAULT_MAX_CORRECTIONS = 2;

    private AgentOutcomeRefiner() {
    }

    // ──────────────────── spec ────────────────────

    /** What kind of judgement a state asked for, if any. */
    sealed interface Judgement permits Decide, Score {
        int maxCorrections();
    }

    record Decide(List<String> options, int maxCorrections) implements Judgement {
    }

    record Band(@Nullable Double atLeast, boolean isDefault, String outcome) {
    }

    record Score(List<Band> bands, int maxCorrections) implements Judgement {
    }

    /** The refined reading of an answer. */
    sealed interface Result permits Decided, NeedsCorrection {
    }

    /** The answer was readable: route on {@code outcome}, store {@code output}. */
    record Decided(String outcome, @Nullable JsonNode output) implements Result {
    }

    /** The answer did not fit; {@code hint} says what was expected. */
    record NeedsCorrection(String hint) implements Result {
    }

    // ──────────────────── reading the spec ────────────────────

    /**
     * The judgement declared on {@code state}, if any.
     *
     * @throws IllegalArgumentException when both are declared — the two
     *         answer different questions and would fight over the outcome
     */
    static Optional<Judgement> judgementOf(MagratheaStateSpec state) {
        Object decide = state.specField("decide");
        Object score = state.specField("score");
        if (decide != null && score != null) {
            throw new IllegalArgumentException(
                    "agent_task '" + state.name() + "' declares both decide: and score:"
                            + " — a step yields one judgement, not two");
        }
        if (decide != null) return Optional.of(readDecide(state.name(), decide));
        if (score != null) return Optional.of(readScore(state.name(), score));
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Decide readDecide(String stateName, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "agent_task '" + stateName + "' decide: must be a map");
        }
        Map<String, Object> m = (Map<String, Object>) map;
        List<String> options = stringList(m.get("options"));
        if (options.isEmpty()) {
            // The binary case is common enough to be worth not spelling out.
            options = List.of("yes", "no");
        }
        return new Decide(options, maxCorrections(m));
    }

    @SuppressWarnings("unchecked")
    private static Score readScore(String stateName, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "agent_task '" + stateName + "' score: must be a map");
        }
        Map<String, Object> m = (Map<String, Object>) map;
        Object bandsRaw = m.get("bands");
        if (!(bandsRaw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "agent_task '" + stateName + "' score: needs a non-empty bands: list");
        }
        List<Band> bands = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> b)) {
                throw new IllegalArgumentException(
                        "agent_task '" + stateName + "' score.bands entries must be maps");
            }
            Map<String, Object> bm = (Map<String, Object>) b;
            String outcome = bm.get("outcome") instanceof String s && !s.isBlank() ? s : null;
            if (outcome == null) {
                throw new IllegalArgumentException(
                        "agent_task '" + stateName + "' score.bands entry is missing outcome:");
            }
            boolean isDefault = Boolean.TRUE.equals(bm.get("default"));
            Double atLeast = bm.get("atLeast") instanceof Number n ? n.doubleValue() : null;
            if (!isDefault && atLeast == null) {
                throw new IllegalArgumentException(
                        "agent_task '" + stateName + "' score.bands entry '" + outcome
                                + "' needs atLeast: or default: true");
            }
            bands.add(new Band(atLeast, isDefault, outcome));
        }
        return new Score(List.copyOf(bands), maxCorrections(m));
    }

    private static int maxCorrections(Map<String, Object> m) {
        Object raw = m.get("maxCorrections");
        if (raw instanceof Number n) return Math.max(0, n.intValue());
        return DEFAULT_MAX_CORRECTIONS;
    }

    private static List<String> stringList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isBlank())
                .toList();
    }

    // ──────────────────── reading the answer ────────────────────

    /** Read {@code answer} as the judgement {@code spec} asked for. */
    static Result refine(Judgement spec, @Nullable String answer, ObjectMapper objectMapper) {
        if (answer == null || answer.isBlank()) {
            return new NeedsCorrection(expectation(spec) + " No answer was given.");
        }
        return switch (spec) {
            case Decide d -> refineDecide(d, answer);
            case Score s -> refineScore(s, answer, objectMapper);
        };
    }

    private static Result refineDecide(Decide spec, String answer) {
        String norm = answer.toLowerCase(Locale.ROOT);
        String found = null;
        int foundAt = Integer.MAX_VALUE;
        for (String option : spec.options()) {
            int at = indexOfToken(norm, option.toLowerCase(Locale.ROOT));
            // First mention wins: a model that reasons then concludes puts
            // its answer last, but one that answers then explains puts it
            // first — and the second is what the prompt asks for.
            if (at >= 0 && at < foundAt) {
                found = option;
                foundAt = at;
            }
        }
        if (found == null) {
            return new NeedsCorrection(expectation(spec));
        }
        return new Decided(found, tools.jackson.databind.node.StringNode.valueOf(found));
    }

    /** Index of {@code token} in {@code text} when it stands as a word. */
    private static int indexOfToken(String text, String token) {
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) return -1;
            boolean leftFree = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + token.length();
            boolean rightFree = end >= text.length()
                    || !Character.isLetterOrDigit(text.charAt(end));
            if (leftFree && rightFree) return at;
            from = at + 1;
        }
    }

    private static Result refineScore(Score spec, String answer, ObjectMapper objectMapper) {
        String json = JsonReplyExtractor.extractLastObject(answer);
        if (json == null) {
            return new NeedsCorrection(expectation(spec) + " No JSON object was found.");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JacksonException ex) {
            return new NeedsCorrection(expectation(spec) + " The JSON did not parse.");
        }
        if (!node.isObject()) {
            return new NeedsCorrection(expectation(spec) + " The JSON was not an object.");
        }
        JsonNode scoreNode = node.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            return new NeedsCorrection(expectation(spec)
                    + " The object had no numeric 'score' field.");
        }
        double score = scoreNode.doubleValue();
        if (score < 0.0 || score > 1.0) {
            // The fixed scale is what makes thresholds portable; a value
            // outside it means the model used a different one, and mapping
            // it anyway would silently mean something else.
            return new NeedsCorrection(expectation(spec)
                    + " The score was " + score + ", outside 0.0–1.0.");
        }
        for (Band band : spec.bands()) {
            if (band.isDefault()) return new Decided(band.outcome(), node);
            if (band.atLeast() != null && score >= band.atLeast()) {
                return new Decided(band.outcome(), node);
            }
        }
        // No band matched and none was declared default: the plan did not
        // say what this score means, which is an authoring gap, not a
        // model error — re-asking cannot fix it, so it fails the state.
        return new Decided("agent_error", node);
    }

    /** What the model should have produced — used as the re-ask. */
    static String expectation(Judgement spec) {
        return switch (spec) {
            case Decide d -> "Answer with exactly one of these words: "
                    + String.join(", ", d.options()) + ".";
            case Score s -> "Answer with a JSON object containing a 'score' "
                    + "field between 0.0 and 1.0.";
        };
    }
}
