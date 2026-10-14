package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The model edges of Wonko: user prompt, result schema and parsing per item
 * type. The instructions live in the recipes ({@code wonko-check},
 * {@code wonko-decompose}, …); this class only hands over the material.
 *
 * <p>Every schema follows plan §4a.8: a defined result instead of a tool call
 * to record it, an escape outcome ({@code cannot_determine} / {@code none} /
 * {@code not_applicable} / {@code unclear}) so the model never has to invent
 * something to fill the form, and a mandatory {@code reason} per decision.
 */
final class WonkoPrompts {

    /** A subject or reference document as read for a call. */
    record SubjectDoc(String path, String content) {}

    /** Result of the whole-subject check (v1 path, no references). */
    record CheckResult(String reason, List<WonkoFinding> findings, List<WonkoCategoryChange> categoryChanges) {}

    /** Result of decomposing the subject into claims. */
    record DecomposeResult(String outcome, String reason, List<WonkoClaim> claims) {}

    /** One candidate the locate call selected. */
    record Selection(String candidateId, String reason) {}

    /** A cross-reference the locate call wants followed. */
    record FollowUp(String term, String reason) {}

    /** Result of selecting applicable passages for a claim. */
    record LocateResult(String outcome, String reason, List<Selection> selections, List<FollowUp> followUps) {}

    /** Verdict about one passage of a compare chunk. */
    record Verdict(
            String passageId,
            String verdict,
            String reason,
            @Nullable WonkoFinding finding) {}

    /** Result of a coverage call over one reference document. */
    record CoverageResult(String outcome, String reason, List<WonkoFinding> missing) {}

    /** User-side mandate change recognised in a free-text answer. */
    record UserMandateChange(
            String category,
            @Nullable MandateLevel level,
            @Nullable Boolean locked) {}

    /** Result of interpreting a free-text answer; {@code unclear} lists the questions it could not map. */
    record InterpretResult(
            List<QuestionAnswerDto> answers,
            boolean stop,
            List<UserMandateChange> mandateChanges,
            List<WonkoDecision> unclear) {}

    static final String CANNOT_DETERMINE = "cannot_determine";

    private static final List<String> CONCERNS = List.of("contradiction", "gap", "vague", "implausible", "intent");

    private static final int MAX_REGISTER_LINES = 60;

    private WonkoPrompts() {}

    // ──────────────────── check (v1, no references) ────────────────────

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
        appendMandate(sb, register.getMandate());
        if (!checklist.isEmpty()) {
            sb.append("\n## Checklist for gaps\n");
            for (String item : checklist) {
                sb.append("- ").append(item).append('\n');
            }
        }
        appendRegister(sb, register);
        appendStatements(sb, userStatements);
        sb.append("\n## Round\n")
                .append(round)
                .append(" of at most ")
                .append(maxRounds)
                .append("\n");
        sb.append("\n## Subject\n");
        appendDocs(sb, subject);
        return sb.toString();
    }

    static Map<String, Object> checkSchema() {
        return object(
                List.of("reason", "findings"),
                Map.of(
                        "reason", string(),
                        "findings", array(findingSchema()),
                        "categoryChanges", array(categoryChangeSchema())));
    }

    static CheckResult parseCheck(Map<String, Object> json) {
        List<WonkoFinding> findings = new ArrayList<>();
        for (Map<?, ?> f : objects(json.get("findings"))) {
            WonkoFinding finding = finding(f, null, null);
            if (finding != null) {
                findings.add(finding);
            }
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
        return new CheckResult(nz(str(json.get("reason"))), findings, changes);
    }

    // ──────────────────── decompose ────────────────────

    static String decomposePrompt(String goal, List<SubjectDoc> subject, int maxClaims, List<String> userStatements) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");
        sb.append("## At most ").append(maxClaims).append(" claims\n");
        appendStatements(sb, userStatements);
        sb.append("\n## Subject\n");
        appendDocs(sb, subject);
        return sb.toString();
    }

    static Map<String, Object> decomposeSchema() {
        Map<String, Object> claim = object(
                List.of("text", "searchTerms", "reason"),
                Map.of(
                        "text", Map.of("type", "string", "minLength", 1),
                        "anchor", string(),
                        "searchTerms", array(string()),
                        "reason", string()));
        return object(
                List.of("outcome", "reason", "claims"),
                Map.of(
                        "outcome", enumOf("ok", "not_decomposable"),
                        "reason", string(),
                        "claims", array(claim)));
    }

    static DecomposeResult parseDecompose(Map<String, Object> json, int maxClaims) {
        List<WonkoClaim> claims = new ArrayList<>();
        for (Map<?, ?> c : objects(json.get("claims"))) {
            String text = str(c.get("text"));
            if (text == null || claims.size() >= maxClaims) {
                continue;
            }
            WonkoClaim claim = new WonkoClaim();
            claim.setText(text);
            claim.setAnchor(str(c.get("anchor")));
            claim.setReason(str(c.get("reason")));
            claim.setSearchTerms(strings(c.get("searchTerms")));
            claims.add(claim);
        }
        return new DecomposeResult(nz(str(json.get("outcome"))), nz(str(json.get("reason"))), claims);
    }

    // ──────────────────── locate ────────────────────

    static String locatePrompt(String goal, WonkoClaim claim, List<WonkoPassageSearch.Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");
        sb.append("## Claim\n").append(claim.getText()).append('\n');
        sb.append("\n## Candidate passages\n");
        for (WonkoPassageSearch.Candidate c : candidates) {
            sb.append("\n### ")
                    .append(c.id())
                    .append(" — ")
                    .append(c.ref())
                    .append("\n````\n")
                    .append(c.text())
                    .append("\n````\n");
        }
        return sb.toString();
    }

    static Map<String, Object> locateSchema() {
        return object(
                List.of("outcome", "reason", "selected"),
                Map.of(
                        "outcome", enumOf("found", "none", CANNOT_DETERMINE),
                        "reason", string(),
                        "selected",
                                array(object(
                                        List.of("candidateId", "reason"),
                                        Map.of("candidateId", string(), "reason", string()))),
                        "followUps",
                                array(object(
                                        List.of("term", "reason"), Map.of("term", string(), "reason", string())))));
    }

    static LocateResult parseLocate(Map<String, Object> json) {
        List<Selection> selections = new ArrayList<>();
        for (Map<?, ?> s : objects(json.get("selected"))) {
            String id = str(s.get("candidateId"));
            if (id != null) {
                selections.add(new Selection(id, nz(str(s.get("reason")))));
            }
        }
        List<FollowUp> followUps = new ArrayList<>();
        for (Map<?, ?> f : objects(json.get("followUps"))) {
            String term = str(f.get("term"));
            if (term != null) {
                followUps.add(new FollowUp(term, nz(str(f.get("reason")))));
            }
        }
        return new LocateResult(nz(str(json.get("outcome"))), nz(str(json.get("reason"))), selections, followUps);
    }

    // ──────────────────── compare ────────────────────

    static String comparePrompt(
            String goal,
            WonkoClaim claim,
            List<WonkoPassage> passages,
            WonkoRegister register,
            List<WonkoTopic> clarifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");
        appendMandate(sb, register.getMandate());
        appendRegister(sb, register);
        sb.append("\n## Claim\n").append(claim.getText()).append('\n');
        if (claim.getAnchor() != null) {
            sb.append("(in the subject: ").append(claim.getAnchor()).append(")\n");
        }
        if (!clarifications.isEmpty()) {
            sb.append("\n## Already clarified for this claim\n")
                    .append("These clarifications amend the claim. The subject text is not edited during the "
                            + "review, so the claim may still read as before: judge the claim AS AMENDED. A "
                            + "contradiction a clarification resolves is no contradiction any more — answer "
                            + "satisfies for it, never ask it again.\n");
            for (WonkoTopic t : clarifications) {
                sb.append("- ")
                        .append(t.getTitle())
                        .append(": ")
                        .append(clarification(t))
                        .append('\n');
            }
        }
        sb.append("\n## Reference passages\n");
        for (WonkoPassage p : passages) {
            sb.append("\n### ")
                    .append(p.getId())
                    .append(" — ")
                    .append(p.getRef())
                    .append("\n````\n")
                    .append(p.getText())
                    .append("\n````\n");
        }
        return sb.toString();
    }

    static Map<String, Object> compareSchema() {
        Map<String, Object> result = new LinkedHashMap<>(findingProperties());
        result.put("passageId", string());
        result.put("verdict", enumOf("contradicts", "satisfies", "not_applicable", CANNOT_DETERMINE));
        result.put("quote", string());
        return object(
                List.of("results"),
                Map.of("results", array(object(List.of("passageId", "verdict", "reason"), result))));
    }

    static List<Verdict> parseCompare(Map<String, Object> json, Map<String, WonkoPassage> passagesById) {
        List<Verdict> out = new ArrayList<>();
        for (Map<?, ?> r : objects(json.get("results"))) {
            String passageId = str(r.get("passageId"));
            String verdict = str(r.get("verdict"));
            if (passageId == null || verdict == null || !passagesById.containsKey(passageId)) {
                continue;
            }
            WonkoFinding finding = null;
            if ("contradicts".equals(verdict)) {
                finding = finding(r, passagesById.get(passageId).getRef(), str(r.get("quote")));
            }
            out.add(new Verdict(passageId, verdict, nz(str(r.get("reason"))), finding));
        }
        return out;
    }

    // ──────────────────── coverage ────────────────────

    static String coveragePrompt(
            String goal, List<SubjectDoc> subject, String ref, List<WonkoPassage> refPassages, WonkoRegister register) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");
        appendMandate(sb, register.getMandate());
        appendRegister(sb, register);
        sb.append("\n## Reference: ").append(ref).append(" — quote obligations ONLY from the text in this section\n");
        for (WonkoPassage p : refPassages) {
            sb.append("\n````\n").append(p.getText()).append("\n````\n");
        }
        sb.append("\n## Subject\n");
        appendDocs(sb, subject);
        return sb.toString();
    }

    static Map<String, Object> coverageSchema() {
        Map<String, Object> missing = new LinkedHashMap<>(findingProperties());
        missing.put("obligation", string());
        missing.put("quote", string());
        return object(
                List.of("outcome", "reason", "missing"),
                Map.of(
                        "outcome", enumOf("complete", "gaps", CANNOT_DETERMINE),
                        "reason", string(),
                        "missing",
                                array(object(List.of("key", "obligation", "quote", "question", "reason"), missing))));
    }

    static CoverageResult parseCoverage(Map<String, Object> json, String ref) {
        List<WonkoFinding> missing = new ArrayList<>();
        for (Map<?, ?> m : objects(json.get("missing"))) {
            WonkoFinding finding = finding(m, ref, str(m.get("quote")));
            if (finding != null) {
                missing.add(finding);
            }
        }
        return new CoverageResult(nz(str(json.get("outcome"))), nz(str(json.get("reason"))), missing);
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
        Map<String, Object> answer = object(
                List.of("key", "outcome", "reason"),
                Map.of(
                        "key", string(),
                        "outcome", enumOf("answered", "settled", "later", "unknown", "unclear"),
                        "value", string(),
                        "reason", string()));
        Map<String, Object> mandateChange = object(
                List.of("category", "reason"),
                Map.of(
                        "category", string(),
                        "level", enumOf("decide", "confirm", "ask"),
                        "locked", Map.of("type", "boolean"),
                        "reason", string()));
        return object(
                List.of("answers", "stop", "reason"),
                Map.of(
                        "answers", array(answer),
                        "stop", Map.of("type", "boolean"),
                        "reason", string(),
                        "mandateChanges", array(mandateChange)));
    }

    static InterpretResult parseInterpret(Map<String, Object> json) {
        List<QuestionAnswerDto> answers = new ArrayList<>();
        List<WonkoDecision> unclear = new ArrayList<>();
        for (Map<?, ?> a : objects(json.get("answers"))) {
            String key = WonkoKeys.topicKey(str(a.get("key")));
            String rawOutcome = str(a.get("outcome"));
            if (key.isEmpty() || rawOutcome == null) {
                continue;
            }
            if ("unclear".equalsIgnoreCase(rawOutcome)) {
                unclear.add(new WonkoDecision(key, "unclear", nz(str(a.get("reason")))));
                continue;
            }
            QuestionOutcome outcome = outcome(rawOutcome);
            if (outcome != null) {
                answers.add(QuestionAnswerDto.builder()
                        .key(key)
                        .outcome(outcome)
                        .value(str(a.get("value")))
                        .build());
            }
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
        return new InterpretResult(answers, Boolean.TRUE.equals(json.get("stop")), changes, unclear);
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

    // ──────────────────── shared material ────────────────────

    private static void appendMandate(StringBuilder sb, WonkoMandate mandate) {
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
    }

    private static void appendRegister(StringBuilder sb, WonkoRegister register) {
        sb.append("\n## Register — topics already known\n")
                .append("(The answers are the user's words, never reference text — do not quote them as "
                        + "evidence.)\n");
        if (register.getTopics().isEmpty()) {
            sb.append("(empty — first review of this subject)\n");
        }
        int lines = 0;
        for (WonkoTopic t : register.getTopics()) {
            if (lines++ >= MAX_REGISTER_LINES) {
                sb.append("- … ")
                        .append(register.getTopics().size() - MAX_REGISTER_LINES)
                        .append(" more\n");
                break;
            }
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
    }

    /** What the user said about a topic, as one phrase. */
    private static String clarification(WonkoTopic t) {
        return switch (t.getStatus()) {
            case SETTLED -> "the user says it is good as it is";
            case DECIDED -> "decided: " + t.getDecision();
            case UNKNOWN -> "the user does not know";
            default -> "the user answered: " + t.getAnswer();
        };
    }

    private static void appendStatements(StringBuilder sb, List<String> userStatements) {
        if (userStatements.isEmpty()) {
            return;
        }
        sb.append("\n## Statements of the user in this run\n");
        for (String s : userStatements) {
            sb.append("- ").append(s).append('\n');
        }
    }

    private static void appendDocs(StringBuilder sb, List<SubjectDoc> docs) {
        for (SubjectDoc doc : docs) {
            sb.append("\n### ")
                    .append(doc.path())
                    .append("\n\n````\n")
                    .append(doc.content())
                    .append("\n````\n");
        }
    }

    // ──────────────────── schema building blocks ────────────────────

    /** Properties every finding-producing result shares. */
    private static Map<String, Object> findingProperties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", string());
        p.put("title", string());
        p.put("category", string());
        p.put("anchor", string());
        p.put("concern", Map.of("type", "string", "enum", CONCERNS));
        p.put("weight", enumOf("blocker", "important", "minor"));
        p.put("question", string());
        p.put("kind", enumOf("choice", "text", "confirm"));
        p.put("options", array(string()));
        p.put("proposal", string());
        p.put("dependsOn", string());
        p.put("context", string());
        p.put("contradictsUser", Map.of("type", "boolean"));
        p.put("reason", string());
        return p;
    }

    private static Map<String, Object> findingSchema() {
        return object(List.of("key", "title", "weight", "question", "reason"), findingProperties());
    }

    private static Map<String, Object> categoryChangeSchema() {
        return object(
                List.of("op", "category", "reason"),
                Map.of(
                        "op", enumOf("define", "describe", "rename", "split", "merge", "level", "classify"),
                        "category", string(),
                        "target", string(),
                        "level", enumOf("decide", "confirm", "ask"),
                        "description", string(),
                        "topic", string(),
                        "reason", string()));
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        return Map.of("type", "object", "required", required, "properties", properties);
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    // ──────────────────── parsing helpers ────────────────────

    /** A finding from any result shape; {@code null} without a usable key. */
    private static @Nullable WonkoFinding finding(
            Map<?, ?> f, @Nullable String evidenceRef, @Nullable String evidenceQuote) {
        String key = WonkoKeys.topicKey(str(f.get("key")));
        if (key.isEmpty()) {
            key = WonkoKeys.topicKey(str(f.get("title")));
        }
        if (key.isEmpty()) {
            return null;
        }
        String category = WonkoKeys.slug(str(f.get("category")));
        String dependsOn = WonkoKeys.topicKey(str(f.get("dependsOn")));
        String title = str(f.get("title"));
        if (title == null) {
            title = str(f.get("obligation"));
        }
        return new WonkoFinding(
                key,
                nz(title),
                category.isEmpty() ? null : category,
                str(f.get("anchor")),
                concern(str(f.get("concern"))),
                weight(str(f.get("weight"))),
                nz(str(f.get("question"))),
                kind(str(f.get("kind"))),
                strings(f.get("options")),
                str(f.get("proposal")),
                dependsOn.isEmpty() ? null : dependsOn,
                evidenceQuote != null ? evidenceQuote : str(f.get("context")),
                Boolean.TRUE.equals(f.get("contradictsUser")),
                str(f.get("reason")),
                evidenceRef,
                evidenceQuote);
    }

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

    private static @Nullable QuestionOutcome outcome(String raw) {
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
