package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.DroppedHit;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Curated research pipeline: takes a free-form question, asks the
 * plan-recipe what to search and how to weight sources, runs the
 * searches through {@link ZarniwoopService} in parallel, hands the
 * raw hits to the evaluate-recipe for scoring, multiplies
 * {@code relevanceScore} by the plan's source-affinity, sorts the
 * survivors and returns them as a {@link RankedHitSet}.
 *
 * <p>v1 omits two pieces called out in the plan (§13):
 *
 * <ul>
 *   <li><b>Deepen</b> — no per-hit content fetch. The evaluate-recipe
 *       sees title + snippet + source and decides without diving
 *       into the body.</li>
 *   <li><b>Refine</b> — no second-wave query reformulation. The plan
 *       produces one wave; if it comes back thin, the caller (or the
 *       end user) decides whether to ask a sharper question.</li>
 * </ul>
 *
 * <p>Both extension points are reserved in the public record
 * ({@code RankedHitSet.refineDepth = 0}) so the upgrade is additive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZarniwoopResearchService {

    static final String RECIPE_PLAN = "zarniwoop-research-plan";
    static final String RECIPE_EVALUATE = "zarniwoop-research-evaluate";

    static final int DEFAULT_MIN_RESULTS = 5;
    static final int DEFAULT_NUM_PER_STEP = 5;
    static final int MAX_PARALLEL_STEPS = 6;

    private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object");

    private final LightLlmService lightLlm;
    private final ZarniwoopService zarniwoopService;
    private final SearchProviderFactory factory;

    /**
     * Run the full pipeline for one question. The {@code ctx} is
     * forwarded to {@link ZarniwoopService} so per-instance failures
     * land in Agrajag with the right tenant/project/user attribution.
     */
    public RankedHitSet investigate(String question, SearchScope scope, ToolInvocationContext ctx) {
        if (StringUtils.isBlank(question)) {
            throw new ZarniwoopException("question is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("research_investigate requires a project scope");
        }

        // 1. PLAN ────────────────────────────────────────────────────
        ResearchPlan plan = plan(question, scope);
        log.debug("Zarniwoop research: plan for '{}' → {} step(s), affinity={}",
                truncate(question, 60), plan.steps().size(), plan.sourceAffinity());

        if (plan.steps().isEmpty()) {
            return new RankedHitSet(
                    question, List.of(), List.of(), 0,
                    Set.of(), plan.sourceAffinity(),
                    List.of("plan-recipe produced no search steps"));
        }

        // 2. EXECUTE ─────────────────────────────────────────────────
        ExecuteOutcome executed = executeAll(plan, scope, ctx);
        if (executed.hits().isEmpty()) {
            return new RankedHitSet(
                    question, List.of(), List.of(), 0,
                    executed.instancesUsed(), plan.sourceAffinity(),
                    List.of("no hits — all candidate instances returned empty or failed"));
        }

        // 3. EVALUATE ────────────────────────────────────────────────
        EvaluateOutcome evaluated = evaluate(question, executed.hits(), scope);

        // 4. RANK ────────────────────────────────────────────────────
        List<RankedHit> kept = new ArrayList<>();
        List<DroppedHit> dropped = new ArrayList<>(evaluated.dropped());
        for (Map.Entry<String, HitWithKey> entry : executed.hits().entrySet()) {
            String hitId = entry.getKey();
            HitWithKey row = entry.getValue();
            EvaluateOutcome.HitVerdict verdict = evaluated.verdicts().get(hitId);
            if (verdict == null || "drop".equalsIgnoreCase(verdict.verdict())) {
                dropped.add(new DroppedHit(
                        row.hit().title(), row.hit().url(),
                        row.hit().modality(), row.providerInstanceId(),
                        verdict == null
                                ? "evaluate-recipe omitted hit"
                                : verdict.dropReason()));
                continue;
            }
            double affinity = sourceAffinityFor(
                    plan.sourceAffinity(), row.hit().modality(), row.providerInstanceId());
            double finalScore = verdict.relevanceScore() * affinity;
            kept.add(new RankedHit(
                    row.hit().title(),
                    row.hit().url(),
                    finalScore,
                    verdict.relevanceScore(),
                    affinity,
                    row.hit().modality(),
                    row.providerInstanceId(),
                    row.hit().snippet(),
                    verdict.relevanceNote(),
                    row.hit().extras()));
        }
        kept.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));

        return new RankedHitSet(
                question,
                kept,
                dropped,
                /* refineDepth */ 0,
                executed.instancesUsed(),
                plan.sourceAffinity(),
                evaluated.gaps());
    }

    // ─── plan ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResearchPlan plan(String question, SearchScope scope) {
        Map<String, Object> providerInventory = describeInventory(scope);
        Map<String, Object> pebbleVars = new LinkedHashMap<>();
        pebbleVars.put("question", question);
        pebbleVars.put("providers", providerInventory.get("instances"));
        pebbleVars.put("modalities", knownModalityNames());

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_PLAN)
                    .userPrompt("Plan a research run for the question above.")
                    .pebbleVars(pebbleVars)
                    .schema(EMPTY_SCHEMA)
                    .tenantId(scope.tenantId())
                    .projectId(scope.projectId())
                    .processId(scope.processId())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Zarniwoop research: plan recipe failed: {}", e.toString());
            return new ResearchPlan(List.of(), Map.of(), DEFAULT_MIN_RESULTS);
        }
        if (raw == null) {
            return new ResearchPlan(List.of(), Map.of(), DEFAULT_MIN_RESULTS);
        }

        Object stepsObj = raw.get("steps");
        List<ResearchPlan.Step> steps = new ArrayList<>();
        if (stepsObj instanceof List<?> list) {
            for (Object s : list) {
                if (!(s instanceof Map<?, ?> rawStep)) continue;
                String mStr = stringField(rawStep, "modality");
                SearchModality modality = parseModality(mStr);
                if (modality == null) continue;
                String query = stringField(rawStep, "query");
                if (StringUtils.isBlank(query)) continue;
                String pin = stringField(rawStep, "instance");
                int num = intField(rawStep, "num", DEFAULT_NUM_PER_STEP);
                if (num < 1) num = 1;
                if (num > 10) num = 10;
                steps.add(new ResearchPlan.Step(
                        modality, query.trim(),
                        StringUtils.isBlank(pin) ? null : pin.trim(),
                        num));
                if (steps.size() >= MAX_PARALLEL_STEPS) break;
            }
        }

        Map<String, Double> affinity = new LinkedHashMap<>();
        Object affObj = raw.get("sourceAffinity");
        if (affObj instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                String key = e.getKey() == null ? null : e.getKey().toString();
                Double val = toDouble(e.getValue());
                if (key != null && val != null) {
                    affinity.put(key, clamp01(val));
                }
            }
        }

        int minResults = intField(raw, "minResults", DEFAULT_MIN_RESULTS);
        if (minResults < 1) minResults = DEFAULT_MIN_RESULTS;

        return new ResearchPlan(List.copyOf(steps), Map.copyOf(affinity), minResults);
    }

    /** Render the inventory the plan-recipe sees as Pebble variable. */
    private Map<String, Object> describeInventory(SearchScope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchProviderInstance inst : factory.assemble(scope)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", inst.id());
            row.put("modalities", inst.modalities().stream().map(Enum::name).toList());
            row.put("domains", inst.domains().stream().map(Enum::name).toList());
            rows.add(row);
        }
        out.put("instances", rows);
        return out;
    }

    private static List<String> knownModalityNames() {
        List<String> out = new ArrayList<>();
        for (SearchModality m : SearchModality.values()) out.add(m.name());
        return out;
    }

    // ─── execute ─────────────────────────────────────────────────────

    private ExecuteOutcome executeAll(ResearchPlan plan, SearchScope scope, ToolInvocationContext ctx) {
        int parallelism = Math.min(plan.steps().size(), MAX_PARALLEL_STEPS);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<SearchResult>> futures = new ArrayList<>(plan.steps().size());
            for (ResearchPlan.Step step : plan.steps()) {
                SearchRequest req = stepRequest(step);
                futures.add(CompletableFuture.supplyAsync(
                        () -> zarniwoopService.search(req, scope, ctx), pool)
                        .exceptionally(t -> {
                            log.warn("Zarniwoop research: step '{}/{}' failed: {}",
                                    step.modality(), step.query(), t.toString());
                            return null;
                        }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // URL-dedupe across all step results. First occurrence wins.
            Map<String, HitWithKey> deduped = new LinkedHashMap<>();
            Set<String> instancesUsed = new LinkedHashSet<>();
            for (CompletableFuture<SearchResult> f : futures) {
                SearchResult result = f.getNow(null);
                if (result == null || !result.ok()) continue;
                instancesUsed.add(result.providerInstanceId());
                for (SearchHit hit : result.hits()) {
                    String key = hit.url();
                    deduped.putIfAbsent(key,
                            new HitWithKey(hit, result.providerInstanceId()));
                }
            }
            // Stable hitId per surviving entry. The evaluate-recipe
            // refers to these ids back into the verdict map.
            Map<String, HitWithKey> indexed = new LinkedHashMap<>();
            int i = 0;
            for (HitWithKey row : deduped.values()) {
                indexed.put("h" + (i++), row);
            }
            return new ExecuteOutcome(indexed, Collections.unmodifiableSet(instancesUsed));
        } finally {
            pool.shutdownNow();
        }
    }

    private static SearchRequest stepRequest(ResearchPlan.Step step) {
        SearchTier tier = StringUtils.isBlank(step.pinnedInstanceId())
                ? SearchTier.NORMAL
                : SearchTier.EXPERT;
        return new SearchRequest(
                step.query(), step.modality(), tier, step.num(),
                null, step.pinnedInstanceId(), Map.of());
    }

    // ─── evaluate ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private EvaluateOutcome evaluate(String question,
                                     Map<String, HitWithKey> hits,
                                     SearchScope scope) {
        // Build the hit table the recipe sees. Each row carries the
        // hitId the recipe must echo back in its verdict.
        List<Map<String, Object>> hitRows = new ArrayList<>(hits.size());
        for (Map.Entry<String, HitWithKey> e : hits.entrySet()) {
            HitWithKey row = e.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hitId", e.getKey());
            r.put("title", row.hit().title());
            r.put("url", row.hit().url());
            r.put("modality", row.hit().modality().name().toLowerCase(Locale.ROOT));
            r.put("providerInstanceId", row.providerInstanceId());
            if (!StringUtils.isBlank(row.hit().snippet())) r.put("snippet", row.hit().snippet());
            if (!StringUtils.isBlank(row.hit().source())) r.put("source", row.hit().source());
            hitRows.add(r);
        }
        Map<String, Object> pebbleVars = Map.of(
                "question", question,
                "hits", hitRows);

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_EVALUATE)
                    .userPrompt("Evaluate the candidate hits for the question.")
                    .pebbleVars(pebbleVars)
                    .schema(EMPTY_SCHEMA)
                    .tenantId(scope.tenantId())
                    .projectId(scope.projectId())
                    .processId(scope.processId())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Zarniwoop research: evaluate recipe failed: {}", e.toString());
            // If evaluate falls over, treat every hit as kept with a
            // neutral score — better some result than none.
            return EvaluateOutcome.failsafeKeepAll(hits.keySet());
        }
        if (raw == null) return EvaluateOutcome.failsafeKeepAll(hits.keySet());

        Map<String, EvaluateOutcome.HitVerdict> verdicts = new HashMap<>();
        List<DroppedHit> droppedSeed = new ArrayList<>();
        Object verdictArr = raw.get("verdicts");
        if (verdictArr instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawVerdict)) continue;
                String hitId = stringField(rawVerdict, "hitId");
                if (StringUtils.isBlank(hitId)) continue;
                String verdict = stringField(rawVerdict, "verdict");
                Double score = toDouble(rawVerdict.get("relevanceScore"));
                String reason = stringField(rawVerdict, "dropReason");
                String note = stringField(rawVerdict, "relevanceNote");
                verdicts.put(hitId, new EvaluateOutcome.HitVerdict(
                        score == null ? 0.0 : clamp01(score),
                        StringUtils.isBlank(verdict) ? "keep" : verdict.trim().toLowerCase(Locale.ROOT),
                        StringUtils.isBlank(reason) ? null : reason.trim(),
                        StringUtils.isBlank(note) ? null : note.trim()));
            }
        }

        List<String> gaps = new ArrayList<>();
        Object gapsObj = raw.get("gaps");
        if (gapsObj instanceof List<?> list) {
            for (Object g : list) {
                if (g instanceof String s && !StringUtils.isBlank(s)) {
                    gaps.add(s.trim());
                }
            }
        }
        return new EvaluateOutcome(verdicts, droppedSeed, gaps);
    }

    // ─── shared helpers ──────────────────────────────────────────────

    private static double sourceAffinityFor(Map<String, Double> affinity,
                                            SearchModality modality,
                                            String instanceId) {
        if (affinity == null || affinity.isEmpty()) return 1.0;
        // The plan can key affinity per "instance:<id>" or
        // per "modality:<name>"; instance wins when both apply.
        Double byInstance = affinity.get("instance:" + instanceId);
        if (byInstance != null) return byInstance;
        Double byModality = affinity.get("modality:"
                + modality.name().toLowerCase(Locale.ROOT));
        if (byModality != null) return byModality;
        return 1.0;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static @Nullable Double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s && !StringUtils.isBlank(s)) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int intField(Map<?, ?> map, String key, int fallback) {
        Object raw = map.get(key);
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !StringUtils.isBlank(s)) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return fallback;
    }

    private static @Nullable String stringField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static @Nullable SearchModality parseModality(@Nullable String name) {
        if (name == null) return null;
        try {
            return SearchModality.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ─── internal records ────────────────────────────────────────────

    record ResearchPlan(List<Step> steps, Map<String, Double> sourceAffinity, int minResults) {
        record Step(SearchModality modality, String query,
                    @Nullable String pinnedInstanceId, int num) { }
    }

    record HitWithKey(SearchHit hit, String providerInstanceId) { }

    record ExecuteOutcome(Map<String, HitWithKey> hits, Set<String> instancesUsed) { }

    record EvaluateOutcome(
            Map<String, HitVerdict> verdicts,
            List<DroppedHit> dropped,
            List<String> gaps) {

        record HitVerdict(double relevanceScore, String verdict,
                          @Nullable String dropReason,
                          @Nullable String relevanceNote) { }

        static EvaluateOutcome failsafeKeepAll(Set<String> hitIds) {
            Map<String, HitVerdict> v = new HashMap<>();
            for (String id : hitIds) {
                v.put(id, new HitVerdict(0.5, "keep", null, null));
            }
            return new EvaluateOutcome(v, List.of(), List.of("evaluate recipe unavailable — neutral scores"));
        }
    }

    /** Used by tests to peek at the recipe wiring. */
    static Map<String, Object> emptySchema() { return EMPTY_SCHEMA; }
}
