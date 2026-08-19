package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * A question for the curated pipeline (plan → multi-source search → evaluate).
 *
 * <p>A question, not a query — the pipeline plans its own searches from it. Note
 * this costs provider quota <b>and</b> LLM tokens, which is why the surface has
 * to make it a named action rather than the search button.
 */
@GenerateTypeScript("search")
public record InvestigateRequestView(String question) {}
