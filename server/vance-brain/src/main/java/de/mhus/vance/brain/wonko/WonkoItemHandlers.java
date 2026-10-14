package de.mhus.vance.brain.wonko;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One handler per check-queue item type (plan §4a.3). A handler reads its
 * material, makes at most one schema-bound model call, and turns the defined
 * result into state: findings into the question pool, claims and passages,
 * follow-up items. Escape outcomes and discards are logged with their reason
 * ({@link WonkoTurn#log}); a quote that does not stand in its source discards
 * the finding before it can reach the user.
 *
 * <p>{@code COMPARE} is split into {@link #compareCall} (the model call, free
 * of state, safe to run in parallel) and {@link #applyCompare} (sequential).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class WonkoItemHandlers {

    private static final int PRIORITY_LOCATE = 10;
    private static final int PRIORITY_RECHECK = 15;
    private static final int PRIORITY_COMPARE = 20;
    private static final int PRIORITY_COVERAGE = 30;

    private final LightLlmService lightLlmService;

    // ──────────────────── CHECK (no references) ────────────────────

    void check(WonkoTurn turn) {
        WonkoPrompts.CheckResult result = WonkoPrompts.parseCheck(call(
                turn,
                turn.params.checkRecipe(),
                WonkoPrompts.checkPrompt(
                        turn.goal,
                        turn.subject(),
                        turn.register,
                        WonkoEngine.stringList(turn.params.get(WonkoEngine.PARAM_CHECKLIST)),
                        turn.state.getUserStatements(),
                        turn.state.getRound() + 1,
                        turn.params.maxRounds()),
                WonkoPrompts.checkSchema()));
        if (result.findings().isEmpty()) {
            turn.log("check of " + String.join(", ", turn.state.getSubjectRefs()), "nothing", result.reason());
        }
        result.findings().forEach(f -> pool(turn, f));
        for (WonkoCategoryChange change : result.categoryChanges()) {
            WonkoMandateRules.Result r = WonkoMandateRules.request(turn.register, change, turn.state.getRound() + 1);
            switch (r.outcome()) {
                case PROPOSED -> turn.state.getProposalPool().add(new WonkoState.PendingProposal("", change));
                case REJECTED ->
                    turn.log("category change " + change.op() + " " + change.category(), "refused", r.description());
                case APPLIED -> {}
            }
        }
    }

    // ──────────────────── DECOMPOSE ────────────────────

    void decompose(WonkoTurn turn) {
        int maxClaims = turn.params.maxClaims();
        WonkoPrompts.DecomposeResult result = WonkoPrompts.parseDecompose(
                call(
                        turn,
                        turn.params.decomposeRecipe(),
                        WonkoPrompts.decomposePrompt(
                                turn.goal, turn.subject(), maxClaims, turn.state.getUserStatements()),
                        WonkoPrompts.decomposeSchema()),
                maxClaims);
        if (result.claims().isEmpty()) {
            // The subject cannot be split: fall back to the whole-subject check
            // instead of ending silently.
            turn.log("decompose", result.outcome().isEmpty() ? "not_decomposable" : result.outcome(), result.reason());
            enqueue(turn.state, WonkoItemType.CHECK, 0, null);
            return;
        }
        for (WonkoClaim claim : result.claims()) {
            claim.setId(nextId(turn.state, "c"));
            turn.state.getClaims().add(claim);
            enqueue(turn.state, WonkoItemType.LOCATE, PRIORITY_LOCATE, claim.getId());
        }
    }

    // ──────────────────── LOCATE ────────────────────

    void locate(WonkoTurn turn, WonkoItem item) {
        WonkoClaim claim = claim(turn.state, item.getClaimId());
        if (claim == null) {
            return;
        }
        List<String> terms = new ArrayList<>(claim.getSearchTerms());
        terms.addAll(item.getTerms());
        List<String> known = turn.state.getPassages().stream()
                .filter(p -> claim.getPassageIds().contains(p.getId()))
                .map(WonkoPassage::getText)
                .toList();
        List<WonkoPassageSearch.Candidate> candidates =
                WonkoPassageSearch.search(turn.references(), terms, turn.params.maxCandidates() + known.size()).stream()
                        .filter(c -> !known.contains(c.text()))
                        .limit(turn.params.maxCandidates())
                        .toList();
        if (candidates.isEmpty()) {
            turn.log(
                    "locate " + claim.getId() + ": " + claim.getText(),
                    "none",
                    "no reference paragraph contains any of: " + String.join(", ", terms));
            queueCompareIfNew(turn, claim);
            return;
        }
        WonkoPrompts.LocateResult result = WonkoPrompts.parseLocate(call(
                turn,
                turn.params.locateRecipe(),
                WonkoPrompts.locatePrompt(turn.goal, claim, candidates),
                WonkoPrompts.locateSchema()));
        Map<String, WonkoPassageSearch.Candidate> byId =
                candidates.stream().collect(Collectors.toMap(WonkoPassageSearch.Candidate::id, Function.identity()));
        for (WonkoPrompts.Selection s : result.selections()) {
            WonkoPassageSearch.Candidate c = byId.get(s.candidateId());
            if (c == null || claim.getPassageIds().size() >= turn.params.maxPassagesPerClaim()) {
                continue;
            }
            WonkoPassage passage = passage(turn.state, c, s.reason());
            if (!claim.getPassageIds().contains(passage.getId())) {
                claim.getPassageIds().add(passage.getId());
            }
            if (turn.params.coverage() && !turn.state.getCoveredRefs().contains(passage.getRef())) {
                turn.state.getCoveredRefs().add(passage.getRef());
                WonkoItem coverage = enqueue(turn.state, WonkoItemType.COVERAGE, PRIORITY_COVERAGE, null);
                coverage.setRef(passage.getRef());
            }
        }
        if (result.selections().isEmpty()) {
            turn.log(
                    "locate " + claim.getId() + ": " + claim.getText(),
                    result.outcome().isEmpty() ? "none" : result.outcome(),
                    result.reason());
        }
        if (item.getDepth() < turn.params.maxLocateDepth()) {
            for (WonkoPrompts.FollowUp f : result.followUps()) {
                WonkoItem follow =
                        enqueue(turn.state, WonkoItemType.LOCATE, PRIORITY_LOCATE + item.getDepth() + 1, claim.getId());
                follow.setTerms(new ArrayList<>(List.of(f.term())));
                follow.setDepth(item.getDepth() + 1);
            }
        }
        queueCompareIfNew(turn, claim);
    }

    // ──────────────────── COMPARE ────────────────────

    /** Passages a compare item works on: all for a recheck, otherwise the not yet compared ones. */
    List<WonkoPassage> comparePassages(WonkoState state, WonkoItem item) {
        WonkoClaim claim = claim(state, item.getClaimId());
        if (claim == null) {
            return List.of();
        }
        return state.getPassages().stream()
                .filter(p -> claim.getPassageIds().contains(p.getId()))
                .filter(p -> item.isRecheck() || !claim.getComparedPassageIds().contains(p.getId()))
                .toList();
    }

    /**
     * The request of a compare item, built on the lane thread — the prompt
     * reads the register, which the sequential apply step changes.
     */
    @Nullable
    LightLlmRequest compareRequest(WonkoTurn turn, WonkoItem item, List<WonkoPassage> passages) {
        WonkoClaim claim = claim(turn.state, item.getClaimId());
        if (claim == null || passages.isEmpty()) {
            return null;
        }
        return request(
                turn,
                turn.params.compareRecipe(),
                WonkoPrompts.comparePrompt(turn.goal, claim, passages, turn.register, item.isRecheck()),
                WonkoPrompts.compareSchema());
    }

    /** The model call alone — touches no state, safe to run in parallel. */
    Map<String, Object> callModel(LightLlmRequest request) {
        return lightLlmService.callForJson(request);
    }

    void applyCompare(WonkoTurn turn, WonkoItem item, List<WonkoPassage> passages, Map<String, Object> json) {
        WonkoClaim claim = claim(turn.state, item.getClaimId());
        if (claim == null || passages.isEmpty()) {
            return;
        }
        turn.state.setCalls(turn.state.getCalls() + 1);
        Map<String, WonkoPassage> byId = new LinkedHashMap<>();
        passages.forEach(p -> byId.put(p.getId(), p));
        for (WonkoPrompts.Verdict v : WonkoPrompts.parseCompare(json, byId)) {
            WonkoPassage passage = byId.get(v.passageId());
            String what = "claim " + claim.getId() + " vs " + passage.getRef();
            switch (v.verdict()) {
                case "contradicts" -> {
                    WonkoFinding f = v.finding();
                    if (f == null) {
                        turn.log(what, "discarded", "contradiction without a usable key or title");
                    } else if (!WonkoQuotes.standsIn(passage.getText(), f.evidenceQuote())) {
                        turn.log(
                                what,
                                "discarded",
                                "quoted evidence does not stand in " + passage.getRef() + ": " + f.evidenceQuote());
                    } else {
                        turn.state.getTopicClaims().put(f.key(), claim.getId());
                        pool(turn, f);
                    }
                }
                case WonkoPrompts.CANNOT_DETERMINE -> unclear(turn, claim, passage, v.reason());
                default -> {}
            }
        }
        passages.forEach(p -> {
            if (!claim.getComparedPassageIds().contains(p.getId())) {
                claim.getComparedPassageIds().add(p.getId());
            }
        });
    }

    // ──────────────────── COVERAGE ────────────────────

    void coverage(WonkoTurn turn, WonkoItem item) {
        String ref = item.getRef();
        String text = ref == null ? null : turn.doc(ref);
        if (ref == null || text == null) {
            return;
        }
        List<WonkoPassage> refPassages = turn.state.getPassages().stream()
                .filter(p -> p.getRef().equals(ref))
                .toList();
        WonkoPrompts.CoverageResult result = WonkoPrompts.parseCoverage(
                call(
                        turn,
                        turn.params.coverageRecipe(),
                        WonkoPrompts.coveragePrompt(turn.goal, turn.subject(), ref, refPassages, turn.register),
                        WonkoPrompts.coverageSchema()),
                ref);
        if (result.missing().isEmpty()) {
            turn.log("coverage " + ref, result.outcome().isEmpty() ? "complete" : result.outcome(), result.reason());
        }
        for (WonkoFinding f : result.missing()) {
            if (WonkoQuotes.standsIn(text, f.evidenceQuote())) {
                pool(turn, f);
            } else {
                turn.log(
                        "coverage " + ref + ": " + f.title(),
                        "discarded",
                        "quoted obligation does not stand in " + ref + ": " + f.evidenceQuote());
            }
        }
    }

    // ──────────────────── queue + state helpers ────────────────────

    static WonkoItem enqueue(WonkoState state, WonkoItemType type, int priority, @Nullable String claimId) {
        WonkoItem item = WonkoItem.of(nextId(state, "i"), type, priority);
        item.setClaimId(claimId);
        state.getQueue().add(item);
        state.getItemCounts()
                .computeIfAbsent(type, t -> new WonkoState.ItemCount())
                .setTotal(state.getItemCounts().get(type).getTotal() + 1);
        return item;
    }

    /** A compare for the claim's passages again, taking the user's answers into account. */
    static void enqueueRecheck(WonkoState state, String claimId) {
        boolean queued = state.getQueue().stream()
                .anyMatch(i -> i.getType() == WonkoItemType.COMPARE && claimId.equals(i.getClaimId()));
        if (!queued) {
            enqueue(state, WonkoItemType.COMPARE, PRIORITY_RECHECK, claimId).setRecheck(true);
        }
    }

    private void queueCompareIfNew(WonkoTurn turn, WonkoClaim claim) {
        boolean hasNew = claim.getPassageIds().stream()
                .anyMatch(id -> !claim.getComparedPassageIds().contains(id));
        boolean queued = turn.state.getQueue().stream()
                .anyMatch(i ->
                        i.getType() == WonkoItemType.COMPARE && claim.getId().equals(i.getClaimId()));
        if (hasNew && !queued) {
            enqueue(turn.state, WonkoItemType.COMPARE, PRIORITY_COMPARE, claim.getId());
        }
        if (claim.getPassageIds().isEmpty() && !hasPendingLocate(turn.state, claim.getId())) {
            turn.log(
                    "claim " + claim.getId() + ": " + claim.getText(),
                    "unchecked",
                    "no applicable reference passage was found for it");
        }
    }

    private static boolean hasPendingLocate(WonkoState state, String claimId) {
        return state.getQueue().stream()
                .anyMatch(i -> i.getType() == WonkoItemType.LOCATE && claimId.equals(i.getClaimId()));
    }

    /** Adds a finding to the question pool — a newer finding on the same key replaces the older one. */
    private static void pool(WonkoTurn turn, WonkoFinding finding) {
        turn.state.getPendingFindings().removeIf(f -> f.key().equals(finding.key()));
        turn.state.getPendingFindings().add(finding);
    }

    /** {@code cannot_determine} never becomes a finding silently — it is reported as unclear (§4a.8). */
    private static void unclear(WonkoTurn turn, WonkoClaim claim, WonkoPassage passage, String reason) {
        String key = "unclear/" + claim.getId() + "-" + WonkoKeys.slug(passage.getRef());
        WonkoTopic topic = turn.register.topic(key).orElseGet(() -> {
            WonkoTopic t = new WonkoTopic();
            t.setKey(key);
            turn.register.getTopics().add(t);
            return t;
        });
        topic.setTitle("Does " + passage.getRef() + " apply to: " + claim.getText());
        topic.setStatus(WonkoTopicStatus.UNCLEAR);
        topic.setFindingReason(reason);
        topic.setEvidenceRef(passage.getRef());
        turn.log("claim " + claim.getId() + " vs " + passage.getRef(), WonkoPrompts.CANNOT_DETERMINE, reason);
    }

    private static WonkoPassage passage(WonkoState state, WonkoPassageSearch.Candidate c, String reason) {
        for (WonkoPassage p : state.getPassages()) {
            if (p.getRef().equals(c.ref()) && p.getText().equals(c.text())) {
                return p;
            }
        }
        WonkoPassage p = new WonkoPassage(nextId(state, "p"), c.ref(), c.text(), reason);
        state.getPassages().add(p);
        return p;
    }

    private static @Nullable WonkoClaim claim(WonkoState state, @Nullable String claimId) {
        return state.getClaims().stream()
                .filter(c -> c.getId().equals(claimId))
                .findFirst()
                .orElse(null);
    }

    private static String nextId(WonkoState state, String prefix) {
        state.setNextId(state.getNextId() + 1);
        return prefix + state.getNextId();
    }

    private Map<String, Object> call(WonkoTurn turn, String recipe, String prompt, Map<String, Object> schema) {
        turn.state.setCalls(turn.state.getCalls() + 1);
        return lightLlmService.callForJson(request(turn, recipe, prompt, schema));
    }

    private static LightLlmRequest request(WonkoTurn turn, String recipe, String prompt, Map<String, Object> schema) {
        return LightLlmRequest.builder()
                .recipeName(recipe)
                .userPrompt(prompt)
                .schema(schema)
                .tenantId(turn.process.getTenantId())
                .projectId(turn.process.getProjectId())
                .processId(turn.process.getId())
                .userId(turn.userId)
                .build();
    }
}
