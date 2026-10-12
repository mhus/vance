package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks questions against the System-One contract and the resolved
 * model's limits, and returns them normalised for the wire. Everything a
 * provider would reject with a 400 is caught here with a message that
 * names the question and the limit — the point of carrying the limits in
 * the catalog.
 *
 * <p>Normalisation: NOUL criteria keys become the strings
 * {@code "true"}/{@code "false"} (YAML reads an unquoted {@code true:}
 * as a boolean key), CHOICE option keys become strings, order is kept.
 *
 * <p>Rules that hold for every model, independent of the catalog:
 * <ul>
 *   <li>names match {@code [a-z0-9_-]{1,64}} and are unique — they end up
 *       as Mongo map keys, which must not contain dots</li>
 *   <li>NOUL criteria are both sides or none (OpenRouter rejects one side,
 *       Ollama accepts it — the stricter rule keeps profiles portable)</li>
 *   <li>at least 2 choice options and 2 score levels</li>
 *   <li>choice option keys contain no {@code '.'} and do not start with
 *       {@code '$'} — they come back as answer and probability keys, which
 *       the engine persists in Mongo maps</li>
 * </ul>
 * The question count is not limited here — the service splits above
 * {@link DecisionModelInfo#maxQuestions()}.
 */
final class ZemQuestionValidator {

    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");

    private ZemQuestionValidator() {}

    static List<ZemQuestionDto> validate(List<ZemQuestionDto> questions, DecisionModelInfo model) {
        if (questions.isEmpty()) {
            throw invalid("at least one question is required");
        }
        String modelLabel = model.provider() + ":" + model.modelName();
        Set<String> names = new HashSet<>();
        List<ZemQuestionDto> out = new ArrayList<>(questions.size());
        for (ZemQuestionDto q : questions) {
            String name = q.getName();
            if (name == null || !NAME.matcher(name).matches()) {
                throw invalid("question name '" + name + "' must match " + NAME.pattern());
            }
            if (!names.add(name)) {
                throw invalid("duplicate question name '" + name + "'");
            }
            if (q.getType() == null) {
                throw invalid("question '" + name + "' has no type");
            }
            requireDescription(q.getInstructions(), true, "question '" + name + "' instructions");
            Object criteria =
                    switch (q.getType()) {
                        case NOUL -> noulCriteria(name, q.getCriteria(), model);
                        case CHOICE -> choiceCriteria(name, q.getCriteria(), model, modelLabel);
                        case SCORE -> scoreCriteria(name, q.getCriteria(), model, modelLabel);
                    };
            out.add(ZemQuestionDto.builder()
                    .name(name)
                    .type(q.getType())
                    .instructions(q.getInstructions())
                    .criteria(criteria)
                    .build());
        }
        return out;
    }

    private static @Nullable Object noulCriteria(String name, @Nullable Object raw, DecisionModelInfo model) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("noul question '" + name + "' criteria must be a map with 'true' and 'false'");
        }
        Object yes = null;
        Object no = null;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(key)) {
                yes = e.getValue();
            } else if ("false".equals(key)) {
                no = e.getValue();
            } else {
                throw invalid("noul question '" + name + "' criteria has unknown key '" + e.getKey()
                        + "' — only 'true' and 'false'");
            }
        }
        if (yes == null || no == null) {
            throw invalid("noul question '" + name + "' criteria needs both 'true' and 'false' (or none at all)");
        }
        requireDescription(yes, model.structuredCriteria(), "noul question '" + name + "' criteria.true");
        requireDescription(no, model.structuredCriteria(), "noul question '" + name + "' criteria.false");
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("true", yes);
        normalized.put("false", no);
        return normalized;
    }

    private static Map<String, Object> choiceCriteria(
            String name, @Nullable Object raw, DecisionModelInfo model, String modelLabel) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("choice question '" + name + "' needs criteria: a map of option -> description");
        }
        int count = map.size();
        if (count < DecisionModelInfo.MIN_OPTIONS || count > model.maxChoiceOptions()) {
            throw invalid("choice question '" + name + "' has " + count + " options — " + modelLabel + " takes "
                    + DecisionModelInfo.MIN_OPTIONS + "–" + model.maxChoiceOptions());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String option = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            if (option.isEmpty()) {
                throw invalid("choice question '" + name + "' has a blank option key");
            }
            if (option.indexOf('.') >= 0 || option.startsWith("$")) {
                throw invalid("choice question '" + name + "' option '" + option
                        + "' must not contain '.' or start with '$' (answers are stored as Mongo map keys)");
            }
            if (e.getValue() != null) {
                requireDescription(
                        e.getValue(),
                        model.structuredCriteria(),
                        "choice question '" + name + "' option '" + option + "'");
            }
            if (normalized.put(option, e.getValue()) != null) {
                throw invalid("choice question '" + name + "' has option '" + option + "' twice");
            }
        }
        return normalized;
    }

    private static List<Object> scoreCriteria(
            String name, @Nullable Object raw, DecisionModelInfo model, String modelLabel) {
        if (!(raw instanceof List<?> list)) {
            throw invalid("score question '" + name + "' needs criteria: a list of levels, lowest first");
        }
        int count = list.size();
        if (count < DecisionModelInfo.MIN_OPTIONS || count > model.maxScoreLevels()) {
            throw invalid("score question '" + name + "' has " + count + " levels — " + modelLabel + " takes "
                    + DecisionModelInfo.MIN_OPTIONS + "–" + model.maxScoreLevels());
        }
        for (int i = 0; i < count; i++) {
            requireDescription(list.get(i), model.structuredCriteria(), "score question '" + name + "' level " + i);
        }
        return new ArrayList<>(list);
    }

    /** A description is a non-blank string, or — where allowed — a non-empty map/list. */
    private static void requireDescription(@Nullable Object value, boolean structuredAllowed, String what) {
        if (value instanceof String s) {
            if (s.isBlank()) {
                throw invalid(what + " is blank");
            }
            return;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (!structuredAllowed) {
                throw invalid(what + " must be a plain string — this model does not take structured"
                        + " descriptions (structuredCriteria: false)");
            }
            if (value instanceof Map<?, ?> m && m.isEmpty() || value instanceof List<?> l && l.isEmpty()) {
                throw invalid(what + " is empty");
            }
            return;
        }
        throw invalid(what + " must be a string" + (structuredAllowed ? ", map or list" : ""));
    }

    private static ZemException invalid(String message) {
        return new ZemException(ZemException.Reason.INVALID_QUESTION, message);
    }

    /** Merge inline questions into profile questions: same name replaces, new names append. */
    static List<ZemQuestionDto> merge(List<ZemQuestionDto> profile, List<ZemQuestionDto> inline) {
        if (inline.isEmpty()) {
            return profile;
        }
        Map<String, ZemQuestionDto> byName = new LinkedHashMap<>();
        for (ZemQuestionDto q : profile) {
            byName.put(q.getName(), q);
        }
        for (ZemQuestionDto q : inline) {
            byName.put(q.getName(), q);
        }
        return List.copyOf(byName.values());
    }
}
