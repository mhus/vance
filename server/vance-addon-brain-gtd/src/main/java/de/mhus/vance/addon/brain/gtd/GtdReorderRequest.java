package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Request body for {@code POST /reorder} (§8b). The server resynthesises the
 * named bucket's order list: ids the caller named that are still in the bucket,
 * in that order, then any bucket Actions the caller did not name. Dead ids are
 * dropped. Writes the {@code _app.yaml} manifest once. {@code bucket} is the
 * wire name ({@code inbox}/{@code today}/{@code upcoming}/{@code anytime}/
 * {@code someday}).
 */
@GenerateTypeScript("gtd")
public record GtdReorderRequest(
        String bucket,
        List<String> orderedIds) {}
