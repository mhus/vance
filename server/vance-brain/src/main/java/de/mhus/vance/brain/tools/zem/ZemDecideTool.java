package de.mhus.vance.brain.tools.zem;

import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemDecisionJson;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemProfileParser;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code zem_decide} — one decision by a System-One model: typed
 * questions about a state, answered with probabilities instead of text.
 *
 * <p>The question shape is flattened for the calling LLM: {@code options}
 * (choice), {@code levels} (score) and {@code yes}/{@code no} (noul) are
 * translated into System-One {@code criteria} here, so the tool schema has
 * no polymorphic field a weak model would fumble. Deferred — advertised by
 * name and search hint only; the manual is {@code manual_read('zem')}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZemDecideTool implements Tool {

    private final ZemService zemService;

    private static final Map<String, Object> QUESTION_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "name",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Answer key, lowercase letters/digits/_/- (e.g. 'intent')."),
                    "type",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("noul", "choice", "score"),
                            "description",
                            "noul = yes/no → p(yes); choice = exactly one of 'options'; "
                                    + "score = position on 'levels'."),
                    "instructions",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "The question. Refer to state fields with backticks, e.g. "
                                    + "'Which intent does `message` express?'."),
                    "options",
                    Map.of(
                            "type",
                            "object",
                            "additionalProperties",
                            Map.of("type", "string"),
                            "description",
                            "choice only: option key → one-line description. 2 or more; keep "
                                    + "'other' last. Keys without '.'."),
                    "levels",
                    Map.of(
                            "type",
                            "array",
                            "items",
                            Map.of("type", "string"),
                            "description",
                            "score only: 2–10 level descriptions, lowest first."),
                    "yes",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "noul only, optional: what counts as yes. Give 'no' too, or neither."),
                    "no",
                    Map.of("type", "string", "description", "noul only, optional: what counts as no.")),
            "required",
            List.of("name", "type", "instructions"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "state",
                    Map.of(
                            "type",
                            "object",
                            "additionalProperties",
                            true,
                            "description",
                            "What to decide about, as an object with meaningful field names "
                                    + "(e.g. {\"message\": \"…\", \"customer\": {\"tier\": \"gold\"}}). "
                                    + "Only relevant fields — noise lowers accuracy. Use 'text' "
                                    + "instead for a plain string."),
                    "text",
                    Map.of("type", "string", "description", "Plain-text state, alternative to 'state'."),
                    "questions",
                    Map.of(
                            "type",
                            "array",
                            "items",
                            QUESTION_SCHEMA,
                            "description",
                            "Typed questions; each is answered independently."),
                    "profile",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional: name of a saved decision profile (recipe with engine: zem) "
                                    + "supplying model and questions. Inline questions replace "
                                    + "profile questions of the same name."),
                    "model",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional model alias or 'instance:model'. Default: default:decision.")),
            "required",
            List.of());

    @Override
    public String name() {
        return "zem_decide";
    }

    @Override
    public String description() {
        return "Decide, classify, route or rate with a decision model (System One): typed questions "
                + "about a state, answered with calibrated probabilities — no prose, ~0.1–0.7 s, "
                + "a fraction of an LLM call. Question types: noul (yes/no → p), choice (exactly one "
                + "option → choice + distribution), score (ordered levels → expected level). Never "
                + "for arithmetic, counting or date comparison — compute those first and put the "
                + "result into the state. Read manual_read('zem') before the first use.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "decide / classify / route / yes-no gate / rate with probabilities, no LLM text";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("zem_decide requires a tenant scope");
        }
        Object state = state(params);
        String profile = string(params, "profile");
        List<ZemQuestionDto> questions = questions(params.get("questions"));
        if (profile == null && questions.isEmpty()) {
            throw new ToolException("zem_decide needs 'questions' or a 'profile'");
        }
        ZemDecideRequest request = ZemDecideRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .profile(profile)
                .questions(questions)
                .model(string(params, "model"))
                .build();
        try {
            ZemDecisionDto decision = zemService.decide(request, state);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("model", decision.getModel());
            out.put("answers", ZemDecisionJson.answers(decision));
            if (decision.getCostUsd() != null) {
                out.put("costUsd", decision.getCostUsd());
            }
            out.put("latencyMs", decision.getLatencyMs());
            return out;
        } catch (ZemException e) {
            log.info("zem_decide failed: reason={} msg={}", e.reason().wire(), e.getMessage());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.reason().wire());
            out.put("message", e.getMessage());
            out.put("retryable", e.retryable());
            return out;
        }
    }

    private static Object state(Map<String, Object> params) {
        Object state = params.get("state");
        String text = string(params, "text");
        if (state != null && text != null) {
            throw new ToolException("zem_decide takes 'state' or 'text', not both");
        }
        if (state instanceof Map<?, ?> || state instanceof List<?> || state instanceof String) {
            return state;
        }
        if (text != null) {
            return text;
        }
        throw new ToolException("zem_decide needs a 'state' object or a 'text'");
    }

    /**
     * Flat tool shape → System-One questions: {@code options} / {@code levels} /
     * {@code yes}+{@code no} become {@code criteria}; validation is the service's.
     */
    static List<ZemQuestionDto> questions(@Nullable Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("'questions' must be an array");
        }
        List<Object> normalized = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> q)) {
                throw new ToolException("each question must be an object");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            q.forEach((k, v) -> copy.put(String.valueOf(k), v));
            Object criteria = criteria(copy);
            copy.remove("options");
            copy.remove("levels");
            copy.remove("yes");
            copy.remove("no");
            if (criteria != null) {
                copy.put("criteria", criteria);
            }
            normalized.add(copy);
        }
        try {
            return ZemProfileParser.questions("zem_decide", normalized);
        } catch (ZemException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static @Nullable Object criteria(Map<String, Object> q) {
        if (q.containsKey("criteria")) {
            return q.get("criteria");
        }
        String type = q.get("type") instanceof String s ? s.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (type) {
            case "choice" -> q.get("options");
            case "score" -> q.get("levels");
            case "noul" -> {
                Object yes = q.get("yes");
                Object no = q.get("no");
                if (yes == null && no == null) {
                    yield null;
                }
                Map<String, Object> both = new LinkedHashMap<>();
                both.put("true", yes);
                both.put("false", no);
                yield both;
            }
            default -> null;
        };
    }

    private static @Nullable String string(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
