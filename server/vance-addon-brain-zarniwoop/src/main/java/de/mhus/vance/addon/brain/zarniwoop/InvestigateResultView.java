package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * What the curated pipeline came back with.
 *
 * <p>{@code gaps} is the part worth surfacing prominently: it is the pipeline
 * saying what it could <i>not</i> answer, which is more useful than a longer
 * list of hits and is the thing a summary would swallow.
 *
 * @param instancesUsed which endpoints contributed, so a person can tell a
 *                      thin answer caused by a thin index from one caused by a
 *                      provider that was down.
 * @param droppedCount  hits the evaluator rejected. Shown as a number rather
 *                      than a list — the rejects are noise, but knowing there
 *                      were forty of them is not.
 */
@GenerateTypeScript("search")
public record InvestigateResultView(
        String question,
        List<RankedHitView> hits,
        int droppedCount,
        List<String> instancesUsed,
        List<String> gaps) {}
