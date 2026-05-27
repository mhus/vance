package de.mhus.vance.brain.prak;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.prak.AffectsAction;
import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.CrossItemRelation;
import de.mhus.vance.shared.prak.CrossItemRelationType;
import de.mhus.vance.shared.prak.Decay;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.EvidenceRole;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.ItemCountExpectation;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.LongTermMemoryDecision;
import de.mhus.vance.shared.prak.Scope;
import de.mhus.vance.shared.prak.ScopeKind;
import de.mhus.vance.shared.prak.TargetRef;
import de.mhus.vance.shared.prak.TargetRefKind;
import de.mhus.vance.shared.prak.WindowSpan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * LLM-driven analyzer for the memory-evaluation pipeline. Thin wrapper
 * around {@link LightLlmService} — same pattern as
 * {@code DiscoveryService}: build the Pebble vars, call the recipe-
 * profiled LLM with a JSON-Schema guard, map the parsed reply into
 * {@link EvaluationOutput}.
 *
 * <p>Returns the <em>raw</em> output — callers run the
 * {@link PrakSanitizer} themselves so each trigger
 * pipeline (compaction-side-channel, hot-path marker, autodream-
 * aggregation, background-consistency) can shape the sanitize
 * context appropriately.
 *
 * <p>Recipe used: {@value #DEFAULT_RECIPE_NAME}. Tenants can override
 * the prompt or model by placing a same-named recipe in their
 * project's recipes cascade.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakService {

    /**
     * Bundled recipe consumed via {@link LightLlmService}. The leading
     * underscore signals the internal-only convention; the recipe also
     * carries {@code internal: true} so the standard selector skips it.
     */
    public static final String DEFAULT_RECIPE_NAME = "_prak";

    /**
     * Pebble variable keys rendered into the recipe's {@code promptPrefix}.
     */
    static final String VAR_MESSAGES = "messages";
    static final String VAR_WINDOW_HINT = "windowHint";
    static final String VAR_EXPECTED_ITEMS_HINT = "expectedItemsHint";

    /**
     * JsonSchemaLight contract — kept permissive (require {@code items}
     * array). Strict per-field validation happens Java-side in
     * {@link #mapEvaluationOutput} so the model isn't penalised for
     * trivia like ordering or extra fields.
     */
    static final Map<String, Object> EVALUATION_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "items", Map.of("type", "array")),
            "required", List.of("items"));

    private final LightLlmService lightLlm;

    /**
     * Run the analyzer over {@code messages}.
     *
     * @param tenantId required — scope for recipe + API-key cascade
     * @param projectId optional project scope for cascades
     * @param processId optional process scope for cascades
     * @param messages span of chat turns to classify (already projected
     *     to {@link SpanMessage} by the caller)
     * @param windowHint short audit string describing the trigger
     *     ("compaction-side-channel", "hot-path: ab jetzt", …)
     * @param expectation soft hint for the LLM about the expected item
     *     count — typically produced by {@link CheapPathFilter#profile}.
     *     {@code null} suppresses the hint in the prompt.
     */
    public EvaluationOutput analyze(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            List<SpanMessage> messages,
            String windowHint,
            @Nullable ItemCountExpectation expectation) {

        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (messages == null || messages.isEmpty()) {
            return EvaluationOutput.empty(buildWindowSpan(List.of()));
        }

        String renderedMessages = renderMessages(messages);
        String expectedHint = expectation == null
                ? "(no estimate)"
                : "Estimated " + expectation.min() + "-" + expectation.max()
                        + " items based on cheap-path pre-filter heuristics. "
                        + "Treat this as orientation, not a constraint.";

        LightLlmRequest req = LightLlmRequest.builder()
                .recipeName(DEFAULT_RECIPE_NAME)
                .userPrompt("Analyse the span above and emit the JSON object.")
                .pebbleVars(Map.of(
                        VAR_MESSAGES, renderedMessages,
                        VAR_WINDOW_HINT, windowHint,
                        VAR_EXPECTED_ITEMS_HINT, expectedHint))
                .schema(EVALUATION_SCHEMA)
                .tenantId(tenantId)
                .projectId(projectId)
                .processId(processId)
                .build();

        Map<String, Object> raw = lightLlm.callForJson(req);
        return mapEvaluationOutput(raw, messages);
    }

    // ──────────────────── Message rendering ────────────────────

    static String renderMessages(List<SpanMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (SpanMessage msg : messages) {
            String turnId = msg.messageId() == null
                    ? "msg-" + idx
                    : msg.messageId();
            sb.append('[').append(turnId).append(" · ")
                    .append(msg.role().name().toLowerCase(Locale.ROOT))
                    .append("] ");
            String content = msg.content() == null ? "" : msg.content().trim();
            sb.append(content).append('\n');
            idx++;
        }
        return sb.toString();
    }

    // ──────────────────── JSON Map → records ────────────────────

    private EvaluationOutput mapEvaluationOutput(
            Map<String, Object> raw, List<SpanMessage> spanForAudit) {
        WindowSpan window = buildWindowSpan(spanForAudit);

        Object itemsRaw = raw.get("items");
        List<ExtractedItem> items = new ArrayList<>();
        AtomicInteger fallbackId = new AtomicInteger(0);
        Set<String> seenIds = new HashSet<>();
        if (itemsRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    log.debug("Prak: dropping non-object item entry: {}", o);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) m;
                ExtractedItem item = mapItem(itemMap, fallbackId, seenIds);
                if (item != null) {
                    items.add(item);
                }
            }
        }

        List<CrossItemRelation> relations = new ArrayList<>();
        Object relRaw = raw.get("crossItemRelations");
        if (relRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                CrossItemRelation rel = mapRelation(m);
                if (rel != null) {
                    relations.add(rel);
                }
            }
        }

        return new EvaluationOutput(window, List.copyOf(items), List.copyOf(relations));
    }

    private static WindowSpan buildWindowSpan(List<SpanMessage> messages) {
        if (messages.isEmpty()) {
            return new WindowSpan(null, null, 0);
        }
        String fromId = messages.get(0).messageId();
        String toId = messages.get(messages.size() - 1).messageId();
        return new WindowSpan(fromId, toId, messages.size());
    }

    private static @Nullable ExtractedItem mapItem(
            Map<String, Object> m,
            AtomicInteger fallbackIdCounter,
            Set<String> seenIds) {

        String content = asString(m.get("content"));
        if (StringUtils.isBlank(content)) {
            // Without content there's nothing to persist or audit.
            return null;
        }

        String id = asString(m.get("id"));
        if (StringUtils.isBlank(id) || seenIds.contains(id)) {
            id = "evt-auto-" + fallbackIdCounter.getAndIncrement();
        }
        seenIds.add(id);

        ItemType type = parseEnum(asString(m.get("type")), ItemType.class, ItemType.FACT);
        int importance = clamp(asInt(m.get("importance"), ExtractedItem.IMPORTANCE_DEFAULT), 0, 5);
        Scope scope = mapScope(m.get("scope"));
        double confidence = clampConfidence(asDouble(m.get("confidence"), 0.5));
        List<String> labels = mapStringList(m.get("labels"));
        List<Evidence> evidence = mapEvidence(m.get("evidence"));
        String why = asNullableString(m.get("why"));
        Decay decay = parseEnum(asString(m.get("decay")), Decay.class, Decay.MEDIUM);
        LongTermMemoryDecision ltm = mapDecision(m.get("longTermMemory"));
        List<AffectsExisting> affects = mapAffectsExisting(m.get("affectsExisting"));

        return new ExtractedItem(
                id, type, importance, content, scope, confidence, labels,
                evidence, why, decay, ltm, affects);
    }

    private static Scope mapScope(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Scope.global();
        }
        ScopeKind kind = parseEnum(asString(m.get("kind")), ScopeKind.class, ScopeKind.PROJECT);
        String id = asNullableString(m.get("id"));
        return new Scope(kind, (id != null && id.isBlank()) ? null : id);
    }

    private static List<Evidence> mapEvidence(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Evidence> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String turnId = asString(m.get("turnId"));
            if (StringUtils.isBlank(turnId)) continue;
            EvidenceRole role = parseEnum(
                    asString(m.get("role")), EvidenceRole.class, EvidenceRole.USER);
            String snippet = asNullableString(m.get("snippet"));
            out.add(new Evidence(turnId, role, snippet));
        }
        return List.copyOf(out);
    }

    private static LongTermMemoryDecision mapDecision(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return LongTermMemoryDecision.skip("missing — analyzer defaulted to SKIP");
        }
        LongTermMemoryAction action = parseLongTermAction(
                asString(m.get("action")), LongTermMemoryAction.SKIP);
        String rationale = asNullableString(m.get("rationale"));
        return new LongTermMemoryDecision(action, rationale);
    }

    /**
     * The LLM tends to emit either {@code "inbox_offer"} or
     * {@code "inboxOffer"}; accept both to keep the prompt forgiving.
     */
    private static LongTermMemoryAction parseLongTermAction(
            @Nullable String raw, LongTermMemoryAction fallback) {
        if (raw == null) return fallback;
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "promote" -> LongTermMemoryAction.PROMOTE;
            case "inboxoffer", "inbox_offer" -> LongTermMemoryAction.INBOX_OFFER;
            case "skip" -> LongTermMemoryAction.SKIP;
            case "refresh" -> LongTermMemoryAction.REFRESH;
            default -> fallback;
        };
    }

    private static List<AffectsExisting> mapAffectsExisting(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<AffectsExisting> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            AffectsAction action = parseEnum(
                    asString(m.get("action")), AffectsAction.class, null);
            if (action == null) continue;
            TargetRef ref = mapTargetRef(m.get("targetRef"));
            if (ref == null) continue;
            String rationale = asNullableString(m.get("rationale"));
            out.add(new AffectsExisting(action, ref, rationale));
        }
        return List.copyOf(out);
    }

    private static @Nullable TargetRef mapTargetRef(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        TargetRefKind kind = parseEnum(
                asString(m.get("kind")), TargetRefKind.class, null);
        if (kind == null) return null;
        return switch (kind) {
            case MEMORY_ID -> {
                String v = asNullableString(m.get("value"));
                yield v == null ? null : TargetRef.byMemoryId(v);
            }
            case LABELS -> {
                List<String> labels = mapStringList(m.get("labels"));
                int minOverlap = clamp(asInt(m.get("minOverlap"), 2), 1, 10);
                yield labels.isEmpty() ? null : TargetRef.byLabels(labels, minOverlap);
            }
            case PATTERN -> {
                String v = asNullableString(m.get("value"));
                yield v == null ? null : TargetRef.byPattern(v);
            }
        };
    }

    private static @Nullable CrossItemRelation mapRelation(Map<?, ?> m) {
        String from = asString(m.get("from"));
        if (StringUtils.isBlank(from)) {
            from = asString(m.get("fromItemId"));
        }
        String to = asString(m.get("to"));
        if (StringUtils.isBlank(to)) {
            to = asString(m.get("toItemId"));
        }
        if (StringUtils.isBlank(from) || StringUtils.isBlank(to)) return null;
        CrossItemRelationType type = parseRelationType(asString(m.get("relation")));
        if (type == null) return null;
        return new CrossItemRelation(from, to, type);
    }

    private static @Nullable CrossItemRelationType parseRelationType(@Nullable String raw) {
        if (raw == null) return null;
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "supersedeswithinbatch", "supersedes_within_batch", "supersedes" ->
                    CrossItemRelationType.SUPERSEDES_WITHIN_BATCH;
            case "extendswithinbatch", "extends_within_batch", "extends" ->
                    CrossItemRelationType.EXTENDS_WITHIN_BATCH;
            default -> null;
        };
    }

    // ──────────────────── Tiny coercion helpers ────────────────────

    private static List<String> mapStringList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static @Nullable String asNullableString(@Nullable Object o) {
        if (o instanceof String s) {
            return s.isBlank() ? null : s;
        }
        return null;
    }

    private static String asString(@Nullable Object o) {
        return o instanceof String s ? s : "";
    }

    private static int asInt(@Nullable Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double asDouble(@Nullable Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double clampConfidence(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static <E extends Enum<E>> @Nullable E parseEnum(
            @Nullable String raw, Class<E> type, @Nullable E fallback) {
        if (raw == null) return fallback;
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, norm);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
