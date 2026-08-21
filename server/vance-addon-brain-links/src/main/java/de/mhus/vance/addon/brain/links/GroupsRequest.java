package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Body of {@code POST /addon/links/groups} — the declared group headings
 * and their order. Groups still carrying entries stay whatever this says,
 * because dropping a heading cannot drop its links.
 */
@GenerateTypeScript("links")
public record GroupsRequest(List<String> groups) {}
