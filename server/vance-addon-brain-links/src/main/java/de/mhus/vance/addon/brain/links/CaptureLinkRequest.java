package de.mhus.vance.addon.brain.links;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/addon/links/capture}.
 *
 * <p>Deliberately smaller than {@link AddLinkRequest}: no {@code teaser} and no
 * {@code image}. Both are the fields that stay empty so the page can speak for
 * itself, and a capture tool is precisely the caller with no business filling
 * them in — it has the page in front of it and would be tempted to paste what it
 * already scraped, freezing today's description into a manifest nobody
 * refreshes. A person who wants an own teaser writes it in the app.
 *
 * <p>{@code title} stays, because a capture tool has one legitimate reason to
 * override it: the reader retitled the entry in the popup before saving.
 */
public record CaptureLinkRequest(
        String url,
        @Nullable String title,
        @Nullable String group,
        @Nullable List<String> tags,
        @Nullable String note) {}
