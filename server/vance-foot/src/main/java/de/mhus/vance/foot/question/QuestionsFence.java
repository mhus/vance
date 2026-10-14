package de.mhus.vance.foot.question;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Finds and parses {@code questions} fences in chat text. The parser is the
 * CLI twin of {@code QuestionsCodec} (vance-shared) and {@code questionsCodec.ts}
 * — foot must not depend on vance-shared — and is held to the same fixture
 * corpus {@code test-fixtures/kind-codecs/questions/}: same rules, same error
 * texts. A stored document's {@code $meta} header is skipped.
 */
public final class QuestionsFence {

    public static final String LANG = "questions";

    /** One fence in a text: the character span of the whole block and its body. */
    public record Found(int start, int end, String body) {}

    private static final Pattern OPEN = Pattern.compile("^\\s*```" + LANG + "(?:\\s.*)?$");
    private static final Pattern CLOSE = Pattern.compile("^\\s*```\\s*$");
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private QuestionsFence() {}

    /** All complete {@code questions} fences in {@code content}, in order. An unclosed fence is not one. */
    public static List<Found> find(String content) {
        List<Found> out = new ArrayList<>();
        Matcher lines = Pattern.compile("(?m)^.*$").matcher(content);
        int start = -1;
        int bodyStart = -1;
        while (lines.find()) {
            String line = lines.group();
            if (start < 0) {
                if (OPEN.matcher(line).matches()) {
                    start = lines.start();
                    bodyStart = Math.min(lines.end() + 1, content.length());
                }
            } else if (CLOSE.matcher(line).matches()) {
                String body = content.substring(bodyStart, Math.max(bodyStart, lines.start()));
                int end = Math.min(lines.end() + 1, content.length());
                out.add(new Found(start, end, body));
                start = -1;
            }
        }
        return out;
    }

    /** The question set of a fence body (YAML; JSON is valid YAML too). */
    public static QuestionSetDto parse(String body) {
        Map<?, ?> root = root(body);
        if (!(root.get("questions") instanceof List<?> list) || list.isEmpty()) {
            throw new QuestionsFenceException("questions: at least one question is required");
        }
        List<QuestionDto> questions = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (Object raw : list) {
            index++;
            if (!(raw instanceof Map<?, ?> q)) {
                throw new QuestionsFenceException("questions[" + index + "]: must be a mapping");
            }
            QuestionDto question = question(q, index);
            if (!keys.add(question.getKey())) {
                throw new QuestionsFenceException(
                        "questions[" + index + "]: duplicate key '" + question.getKey() + "'");
            }
            questions.add(question);
        }
        String id = str(root.get("id"));
        return QuestionSetDto.builder()
                .id(id == null ? "" : id)
                .intro(str(root.get("intro")))
                .questions(questions)
                .build();
    }

    private static Map<?, ?> root(String body) {
        if (body.isBlank()) {
            return Map.of();
        }
        Object parsed;
        try {
            parsed = YAML.readValue(body, Object.class);
        } catch (JacksonException e) {
            throw new QuestionsFenceException("Invalid YAML: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new QuestionsFenceException("Top-level must be a mapping");
        }
        return map;
    }

    private static QuestionDto question(Map<?, ?> q, int index) {
        String key = str(q.get("key"));
        String text = str(q.get("text"));
        if (key == null) {
            throw new QuestionsFenceException("questions[" + index + "]: key is required");
        }
        if (text == null) {
            throw new QuestionsFenceException("questions[" + index + "]: text is required");
        }
        QuestionKind kind = kind(str(q.get("kind")), index);
        List<QuestionOptionDto> options = options(q.get("options"), index);
        if (kind == QuestionKind.CHOICE && options.size() < 2) {
            throw new QuestionsFenceException("questions[" + index + "]: a choice needs at least two options");
        }
        return QuestionDto.builder()
                .key(key)
                .text(text)
                .kind(kind)
                .options(new ArrayList<>(options))
                .proposal(str(q.get("proposal")))
                .context(str(q.get("context")))
                .weight(weight(str(q.get("weight"))))
                .build();
    }

    private static QuestionKind kind(@Nullable String raw, int index) {
        if (raw == null) {
            return QuestionKind.TEXT;
        }
        try {
            return QuestionKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new QuestionsFenceException(
                    "questions[" + index + "]: kind must be choice, text or confirm (got '" + raw + "')", e);
        }
    }

    private static QuestionWeight weight(@Nullable String raw) {
        if (raw == null) {
            return QuestionWeight.IMPORTANT;
        }
        try {
            return QuestionWeight.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return QuestionWeight.IMPORTANT;
        }
    }

    private static List<QuestionOptionDto> options(@Nullable Object raw, int index) {
        List<QuestionOptionDto> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            throw new QuestionsFenceException("questions[" + index + "]: options must be a list");
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                String label = str(m.get("label"));
                if (label != null) {
                    out.add(QuestionOptionDto.builder()
                            .label(label)
                            .description(str(m.get("description")))
                            .build());
                }
            } else if (o != null && !String.valueOf(o).isBlank()) {
                out.add(QuestionOptionDto.builder()
                        .label(String.valueOf(o).trim())
                        .build());
            }
        }
        return out;
    }

    private static @Nullable String str(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }
}
