package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Ask a provider for the body behind a hit.
 *
 * <p>The three fields together are what {@code SearchProviderInstance
 * .loadContent} needs as a {@code ContentReference}, which the server rebuilds —
 * the reference is not held anywhere between the search and the click. That is
 * the honest shape for a stateless surface: keeping a per-hit reference in server
 * memory would make the click depend on which pod answered the search.
 *
 * @param instanceId which provider endpoint produced the hit; the content of a
 *                   hit can only be fetched from the source that found it.
 * @param mimeType   what the search said the body is, passed back so a provider
 *                   that does not repeat it on the fetch still yields the right
 *                   content type.
 */
@GenerateTypeScript("search")
public record ContentRequestView(
        String instanceId,
        String contentId,
        @Nullable String mimeType) {}
