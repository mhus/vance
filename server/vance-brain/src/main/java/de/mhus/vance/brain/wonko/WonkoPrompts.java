package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The two model edges of Wonko: the user prompt and schema of the check call,
 * the same for the interpretation of a free-text answer, and the parsing of
 * both replies into typed values. The instructions themselves live in the
 * recipes {@code wonko-check} and {@code wonko-interpret}; this class only
 * hands over the material.
 */
final class WonkoPrompts {

    /** A subject document as read for the check. */
    record SubjectDoc(String path, String content) {}

    /** Result of the check call. */
    record CheckResult(List<WonkoFinding> findings, List<WonkoCategoryChange> categoryChanges) {}

    /** User-side mandate change recognised in a free-text answer. */
    record UserMandateChange(
            String category,
            @Nullable MandateLevel level,
            @Nullable Boolean locked) {}

    /** Result of interpreting a free-text answer. */
    record InterpretResult(List<QuestionAnswerDto> answers, boolean stop, List<UserMandateChange> mandateChanges) {}

    private static final List<String> CONCERNS = List.of("contradiction", "gap", "vague", "implausible", "intent");

    private WonkoPrompts() {}

    // ──────────────────── check ────────────────────

    static String checkPrompt(
            String goal,
            List<SubjectDoc> subject,
            WonkoRegister register,
            List<String> checklist,
            List<String> userStatements,
            int round,
            int maxRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");
        WonkoMandate mandate = register.getMandate();
        if (mandate.getNotes() != null) {
            sb.append("## Notes from the user\n").append(mandate.getNotes()).append("\n\n");
        }
        sb.append("## Categories\n");
        if (mandate.getCategories().isEmpty()) {
            sb.append("(none yet — define the categories this subject needs)\n");
        }
        mandate.getCategories()
                .forEach((name, c) -> sb.append("- ")
                        .append(name)
                        .append(c.getDescription() == null ? "" : ": " + c.getDescription())
                        .append('\n'));
        if (!checklist.isEmpty()) {
            sb.append("\n## Checklist for gaps\n");
            for (String item : checklist) {
                sb.append("- ").append(item).append('\n');
            }
        }
        sb.append("\n## Register — topics already known\n");
        if (register.getTopics().isEmpty()) {
            sb.append("(empty — first review of this subject)\n");
        }
        for (WonkoTopic t : register.getTopics()) {
            sb.append("- `")
                    .append(t.getKey())
                    .append("` [")
                    .append(t.getStatus().name())
                    .append("] ")
                    .append(t.getTitle());
            switch (t.getStatus()) {
                case ANSWERED -> sb.append(" — answer: ").append(t.getAnswer());
                case DECIDED -> sb.append(" — decided: ").append(t.getDecision());
                case SETTLED -> sb.append(" — the user said it is good as it is; the whole branch is closed");
                default -> {}
            }
            sb.append('\n');
        }
        if (!userStatements.isEmpty()) {
            sb.append("\n## Statements of the user in this run\n");
            for (String s : userStatements) {
                sb.append("- ").append(s).append('\n');
            }
        }
        sb.append("\n## Round\n")
                .append(round)
                .append(" of at most ")
                .append(maxRounds)
                .append("\n");
        sb.append("\n## Subject\n");
        for (SubjectDoc doc : subject) {
            sb.append("\n### ")
                    .append(doc.path())
                    .append("\n\n````\n")
                    .append(doc.content())
                    .append("\n````\n");
        }
        return sb.toString();
    }

    static Map<String, Object> checkSchema() {
        Map<String, Object> finding = Map.of(
                "type", "object",
                "required", List.of("key", "title", "weight", "question"),
                "properties",
                        Map.ofEntries(
                                Map.entry("key", Map.of("type", "string", "minLength", 1)),
                                Map.entry("title", Map.of("type", "string")),
                                Map.entry("category", Map.of("type", "string")),
                                Map.entry("anchor", Map.of("type", "string")),
                                Map.entry("concern", Map.of("type", "string", "enum", CONCERNS)),
                                Map.entry(
                                        "weight",
                                        Map.of("type", "string", "enum", List.of("blocker", "important", "minor"))),
                                Map.entry("question", Map.of("type", "string")),
                                Map.entry(
                                        "kind", Map.of("type", "string", "enum", List.of("choice", "text", "confirm"))),
                                Map.entry("options", Map.of("type", "array", "items", Map.of("type", "string"))),
                                Map.entry("proposal", Map.of("type", "string")),
                                Map.entry("dependsOn", Map.of("type", "string")),
                                Map.entry("context", Map.of("type", "string")),
                                Map.entry("contradictsUser", Map.of("type", "boolean"))));
        Map<String, Object> change = Map.of(
                "type", "object",
                "required", List.of("op", "category"),
                "properties",
                        Map.of(
                                "op",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of(
                                                        "define",
                                                        "describe",
                                                        "rename",
                                                        "split",
                                                        "merge",
                                                        "level",
                                                        "classify")),
                                "category", Map.of("type", "string"),
                                "target", Map.of("type", "string"),
                                "level", Map.of("type", "string", "enum", List.of("decide", "confirm", "ask")),
                                "description", Map.of("type", "string"),
                                "topic", Map.of("type", "string"),
                                "reason", Map.of("type", "string")));
        return Map.of(
                "type", "object",
                "required", List.of("findings"),
                "properties",
                        Map.of(
                                "findings", Map.of("type", "array", "items", finding),
                                "categoryChanges", Map.of("type", "array", "items", change)));
    }

    static CheckResult parseCheck(Map<String, Object> json) {
        List<WonkoFinding> findings = new ArrayList<>();
        for (Map<?, ?> f : objects(json.get("findings"))) {
            String key = WonkoKeys.topicKey(str(f.get("key")));
            if (key.isEmpty()) {
                continue;
            }
            String category = WonkoKeys.slug(str(f.get("category")));
            String dependsOn = WonkoKeys.topicKey(str(f.get("dependsOn")));
            findings.add(new WonkoFinding(
                    key,
                    nz(str(f.get("title"))),
                    category.isEmpty() ? null : category,
                    str(f.get("anchor")),
                    concern(str(f.get("concern"))),
                    weight(str(f.get("weight"))),
                    nz(str(f.get("question"))),
                    kind(str(f.get("kind"))),
                    strings(f.get("options")),
                    str(f.get("proposal")),
                    dependsOn.isEmpty() ? null : dependsOn,
                    str(f.get("context")),
                    Boolean.TRUE.equals(f.get("contradictsUser"))));
        }
        List<WonkoCategoryChange> changes = new ArrayList<>();
        for (Map<?, ?> c : objects(json.get("categoryChanges"))) {
            WonkoCategoryChange.Op op = op(str(c.get("op")));
            String category = str(c.get("category"));
            if (op == null || category == null) {
                continue;
            }
            changes.add(new WonkoCategoryChange(
                    op,
                    category,
                    str(c.get("target")),
                    MandateLevel.parse(c.get("level")),
                    str(c.get("description")),
                    str(c.get("topic")),
                    str(c.get("reason"))));
        }
        return new CheckResult(findings, changes);
    }

    // ──────────────────── interpret ────────────────────

    static String interpretPrompt(QuestionSetDto set, String userText) {
        StringBuilder sb = new StringBuilder("## Open questions\n");
        for (QuestionDto q : set.getQuestions()) {
            sb.append("- `")
                    .append(q.getKey())
                    .append("` (")
                    .append(q.getKind().name().toLowerCase(Locale.ROOT))
                    .append(") ")
                    .append(q.getText());
            if (q.getProposal() != null) {
                sb.append(" — proposal: ").append(q.getProposal());
            }
            if (!q.getOptions().isEmpty()) {
                sb.append(" — options: ")
                        .append(String.join(
                                " | ",
                                q.getOptions().stream().map(o -> o.getLabel()).toList()));
            }
            sb.append('\n');
        }
        sb.append("\n## The user's reply\n").append(userText).append('\n');
        return sb.toString();
    }

    static Map<String, Object> interpretSchema() {
        Map<String, Object> answer = Map.of(
                "type", "object",
                "required", List.of("key", "outcome"),
                "properties",
                        Map.of(
                                "key", Map.of("type", "string"),
                                "outcome",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("answered", "settled", "later", "unknown")),
                                "value", Map.of("type", "string")));
        Map<String, Object> mandateChange = Map.of(
                "type", "object",
                "required", List.of("category"),
                "properties",
                        Map.of(
                                "category", Map.of("type", "string"),
                                "level", Map.of("type", "string", "enum", List.of("decide", "confirm", "ask")),
                                "locked", Map.of("type", "boolean")));
        return Map.of(
                "type", "object",
                "required", List.of("answers", "stop"),
                "properties",
                        Map.of(
                                "answers", Map.of("type", "array", "items", answer),
                                "stop", Map.of("type", "boolean"),
                                "mandateChanges", Map.of("type", "array", "items", mandateChange)));
    }

    static InterpretResult parseInterpret(Map<String, Object> json) {
        List<QuestionAnswerDto> answers = new ArrayList<>();
        for (Map<?, ?> a : objects(json.get("answers"))) {
            String key = WonkoKeys.topicKey(str(a.get("key")));
            QuestionOutcome outcome = outcome(str(a.get("outcome")));
            if (key.isEmpty() || outcome == null) {
                continue;
            }
            answers.add(QuestionAnswerDto.builder()
                    .key(key)
                    .outcome(outcome)
                    .value(str(a.get("value")))
                    .build());
        }
        List<UserMandateChange> changes = new ArrayList<>();
        for (Map<?, ?> m : objects(json.get("mandateChanges"))) {
            String category = str(m.get("category"));
            if (category == null) {
                continue;
            }
            Object locked = m.get("locked");
            changes.add(new UserMandateChange(
                    category, MandateLevel.parse(m.get("level")), locked instanceof Boolean b ? b : null));
        }
        return new InterpretResult(answers, Boolean.TRUE.equals(json.get("stop")), changes);
    }

    // ──────────────────── question message ────────────────────

    /** Readable Markdown form of a set — the chat content beside the structured meta. */
    static String renderQuestionSet(QuestionSetDto set, List<WonkoTopic> decided) {
        StringBuilder sb = new StringBuilder();
        if (set.getIntro() != null) {
            sb.append(set.getIntro()).append("\n\n");
        }
        int i = 1;
        for (QuestionDto q : set.getQuestions()) {
            sb.append(i++).append(". ");
            if (q.getWeight() == QuestionWeight.BLOCKER) {
                sb.append("**[blocker]** ");
            }
            sb.append(q.getText());
            if (q.getKind() == QuestionKind.CONFIRM && q.getProposal() != null) {
                sb.append("\n   *My assumption:* ").append(q.getProposal());
            }
            if (!q.getOptions().isEmpty()) {
                sb.append("\n   Options: ")
                        .append(String.join(
                                " · ",
                                q.getOptions().stream().map(o -> o.getLabel()).toList()));
            }
            if (q.getContext() != null) {
                sb.append("\n   > ").append(q.getContext());
            }
            sb.append('\n');
        }
        if (!decided.isEmpty()) {
            sb.append("\nDecided within my mandate:\n");
            for (WonkoTopic t : decided) {
                sb.append("- **")
                        .append(t.getTitle())
                        .append("** → ")
                        .append(t.getDecision())
                        .append('\n');
            }
        }
        sb.append("\nPer question you can also answer \"good as it is\", \"later\" or \"don't know\".");
        return sb.toString();
    }

    // ──────────────────── helpers ────────────────────

    private static List<Map<?, ?>> objects(@Nullable Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static List<String> strings(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static @Nullable String str(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static @Nullable String concern(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.toLowerCase(Locale.ROOT);
        return CONCERNS.contains(c) ? c : null;
    }

    private static QuestionWeight weight(@Nullable String raw) {
        if (raw == null) {
            return QuestionWeight.IMPORTANT;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "blocker" -> QuestionWeight.BLOCKER;
            case "minor" -> QuestionWeight.MINOR;
            default -> QuestionWeight.IMPORTANT;
        };
    }

    private static QuestionKind kind(@Nullable String raw) {
        if (raw == null) {
            return QuestionKind.TEXT;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "choice" -> QuestionKind.CHOICE;
            case "confirm" -> QuestionKind.CONFIRM;
            default -> QuestionKind.TEXT;
        };
    }

    private static @Nullable QuestionOutcome outcome(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "answered" -> QuestionOutcome.ANSWERED;
            case "settled" -> QuestionOutcome.SETTLED;
            case "later" -> QuestionOutcome.LATER;
            case "unknown" -> QuestionOutcome.UNKNOWN;
            default -> null;
        };
    }

    private static WonkoCategoryChange.@Nullable Op op(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return WonkoCategoryChange.Op.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
