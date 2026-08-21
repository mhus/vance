package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Body of {@code POST /addon/links/reorder} — the full display order as
 * the client is showing it. URLs the server does not know are ignored and
 * entries the client did not send keep their relative order at the tail,
 * so a list that changed underneath is not truncated by a stale drag.
 */
@GenerateTypeScript("links")
public record ReorderLinksRequest(List<String> orderedUrls) {}
