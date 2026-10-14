package de.mhus.vance.shared.document.kind;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: questions} — several questions to
 * the user at once, each answered separately (spec
 * {@code specification/public/doc-kind-questions.md}). Mirrors
 * {@code questionsCodec.ts}; the shared fixture corpus
 * {@code test-fixtures/kind-codecs/questions/} pins the agreement.
 *
 * <p>Body is YAML (an inline {@code ```questions} fence or a stored
 * document with a {@code $meta} header):
 *
 * <pre>
 * id: q-7f3a                 # optional
 * intro: Three points about the requirement
 * questions:
 *   - key: eu-region
 *     text: Which region stores the data?
 *     kind: choice           # choice | text | confirm (default text)
 *     options: [eu-central-1, eu-west-1]
 *     context: "must be stored … within the European Union"
 *   - key: retention
 *     text: Correct the retention to 10 years?
 *     kind: confirm
 *     proposal: 10 years instead of 5
 * </pre>
 *
 * Strict where a renderer could not cope (no questions, missing key or text,
 * duplicate keys, a choice without options); lenient on case and on options
 * written as plain strings.
 */
public final class QuestionsCodec {

    public static final String KIND = "questions";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP = new TypeReference<>() {};

    private QuestionsCodec() {}

    public static boolean supports(@Nullable String mimeType) {
        return mimeType == null
                || "application/json".equals(mimeType)
                || mimeType.isBlank()
                || "application/yaml".equals(mimeType)
                || "application/x-yaml".equals(mimeType)
                || "text/yaml".equals(mimeType)
                || "text/x-yaml".equals(mimeType)
                || "text/markdown".equals(mimeType)
                || "text/plain".equals(mimeType);
    }

    public static QuestionSetDto parse(String body, @Nullable String mimeType) {
        Map<String, Object> root =
                "application/json".equals(mimeType) ? parseJson(body) : KindHeaderCodec.parseYamlBody(body);
        List<QuestionDto> questions = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        if (!(root.get("questions") instanceof List<?> list) || list.isEmpty()) {
            throw new KindCodecException("questions: at least one question is required");
        }
        int index = 0;
        for (Object raw : list) {
            index++;
            if (!(raw instanceof Map<?, ?> q)) {
                throw new KindCodecException("questions[" + index + "]: must be a mapping");
            }
            QuestionDto question = question(q, index);
            if (!keys.add(question.getKey())) {
                throw new KindCodecException("questions[" + index + "]: duplicate key '" + question.getKey() + "'");
            }
            questions.add(question);
        }
        return QuestionSetDto.builder()
                .id(nz(str(root.get("id"))))
                .intro(str(root.get("intro")))
                .questions(questions)
                .build();
    }

    /** Stored-document form: {@code $meta} header with the kind, then the body (YAML, or JSON on request). */
    public static String serialize(QuestionSetDto set, @Nullable String mimeType) {
        if ("application/json".equals(mimeType)) {
            return JSON.writeValueAsString(KindHeaderCodec.wrapJsonMeta(KIND, body(set)));
        }
        return KindHeaderCodec.dumpYamlBody(KIND, body(set));
    }

    private static Map<String, Object> parseJson(String body) {
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) {
            throw new KindCodecException("Top-level JSON must be an object");
        }
        return KindHeaderCodec.unwrapJsonMeta(parsed);
    }

    /** The inline chat form: a {@code ```questions} fence around the YAML body (no header). */
    public static String toFence(QuestionSetDto set) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(100);
        opts.setSplitLines(false);
        String yaml = new Yaml(new Representer(opts), opts).dump(body(set));
        return "```" + KIND + "\n" + yaml + "```\n";
    }

    private static Map<String, Object> body(QuestionSetDto set) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!set.getId().isBlank()) {
            body.put("id", set.getId());
        }
        if (set.getIntro() != null) {
            body.put("intro", set.getIntro());
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (QuestionDto q : set.getQuestions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", q.getKey());
            m.put("text", q.getText());
            m.put("kind", q.getKind().name().toLowerCase(Locale.ROOT));
            if (!q.getOptions().isEmpty()) {
                List<Object> options = new ArrayList<>();
                for (QuestionOptionDto o : q.getOptions()) {
                    if (o.getDescription() == null) {
                        options.add(o.getLabel());
                    } else {
                        Map<String, Object> om = new LinkedHashMap<>();
                        om.put("label", o.getLabel());
                        om.put("description", o.getDescription());
                        options.add(om);
                    }
                }
                m.put("options", options);
            }
            if (q.getProposal() != null) {
                m.put("proposal", q.getProposal());
            }
            if (q.getContext() != null) {
                m.put("context", q.getContext());
            }
            if (q.getWeight() != QuestionWeight.IMPORTANT) {
                m.put("weight", q.getWeight().name().toLowerCase(Locale.ROOT));
            }
            questions.add(m);
        }
        body.put("questions", questions);
        return body;
    }

    private static QuestionDto question(Map<?, ?> q, int index) {
        String key = str(q.get("key"));
        String text = str(q.get("text"));
        if (key == null) {
            throw new KindCodecException("questions[" + index + "]: key is required");
        }
        if (text == null) {
            throw new KindCodecException("questions[" + index + "]: text is required");
        }
        QuestionKind kind = kind(str(q.get("kind")), index);
        List<QuestionOptionDto> options = options(q.get("options"), index);
        if (kind == QuestionKind.CHOICE && options.size() < 2) {
            throw new KindCodecException("questions[" + index + "]: a choice needs at least two options");
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
            throw new KindCodecException(
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
            throw new KindCodecException("questions[" + index + "]: options must be a list");
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && str(m.get("label")) != null) {
                out.add(QuestionOptionDto.builder()
                        .label(str(m.get("label")))
                        .description(str(m.get("description")))
                        .build());
            } else if (o != null
                    && !(o instanceof Map<?, ?>)
                    && !String.valueOf(o).isBlank()) {
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

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }
}
