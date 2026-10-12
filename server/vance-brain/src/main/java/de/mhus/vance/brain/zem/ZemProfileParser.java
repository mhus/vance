package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code params} of an {@code engine: zem} recipe into a
 * {@link ZemProfile}:
 *
 * <pre>
 * params:
 *   model: openrouter:typesafe/jev-1.13     # optional
 *   stateSelect: [message, customer.tier]   # optional
 *   questions:
 *     - name: unsafe
 *       type: noul                           # noul | choice | score
 *       instructions: "…"                    # string, map or list
 *       criteria: {…}                        # per type, see ZemQuestionDto
 * </pre>
 *
 * Shape errors only — limits are checked later against the resolved
 * model by {@link ZemQuestionValidator}.
 */
public final class ZemProfileParser {

    private ZemProfileParser() {}

    static ZemProfile parse(String recipeName, Map<String, Object> params) {
        String model = params.get("model") instanceof String s && !s.isBlank() ? s.trim() : null;
        return new ZemProfile(
                recipeName,
                model,
                questions(recipeName, params.get("questions")),
                stateSelect(recipeName, params.get("stateSelect")));
    }

    /** Questions from a list of maps — the shape recipes and tool input share. */
    public static List<ZemQuestionDto> questions(String source, @Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(source, "'questions' must be a list of question maps");
        }
        List<ZemQuestionDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw invalid(source, "questions[" + i + "] must be a map");
            }
            out.add(question(source, i, map));
        }
        return out;
    }

    private static ZemQuestionDto question(String source, int index, Map<?, ?> map) {
        Object name = map.get("name");
        if (!(name instanceof String n) || n.isBlank()) {
            throw invalid(source, "questions[" + index + "] has no 'name'");
        }
        Object type = map.get("type");
        if (!(type instanceof String t) || t.isBlank()) {
            throw invalid(source, "question '" + n + "' has no 'type' (noul, choice or score)");
        }
        ZemQuestionType parsed;
        try {
            parsed = ZemQuestionType.valueOf(t.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ZemException(
                    ZemException.Reason.INVALID_PROFILE,
                    "Zem profile '" + source + "': question '" + n + "' has unknown type '" + t
                            + "' (noul, choice or score)",
                    e);
        }
        Object instructions = map.get("instructions");
        if (instructions == null) {
            throw invalid(source, "question '" + n + "' has no 'instructions'");
        }
        return ZemQuestionDto.builder()
                .name(n.trim())
                .type(parsed)
                .instructions(instructions)
                .criteria(map.get("criteria"))
                .build();
    }

    private static List<String> stateSelect(String source, @Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(source, "'stateSelect' must be a list of field paths");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String path) || path.isBlank()) {
                throw invalid(source, "'stateSelect' entries must be non-blank field paths");
            }
            out.add(path.trim());
        }
        return out;
    }

    private static ZemException invalid(String source, String message) {
        return new ZemException(ZemException.Reason.INVALID_PROFILE, "Zem profile '" + source + "': " + message);
    }
}
