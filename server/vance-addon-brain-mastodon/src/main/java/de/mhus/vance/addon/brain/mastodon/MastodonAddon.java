package de.mhus.vance.addon.brain.mastodon;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Mastodon addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans this
 * package so {@link MastodonFeedProtocol} registers itself as a
 * {@code FeedProtocol} bean — which is all a Centauri source needs.
 * {@code FeedSourceFactory} collects every such bean by {@code id()} in its
 * constructor, so there is nothing to wire and no REST surface to add.
 *
 * <p>Brain-only: no {@code client/}, because the Feeds app draws a source from
 * its declared capabilities. Not loading this addon simply removes the
 * {@code mastodon} protocol; endpoints configured for it are then skipped with
 * a warning, like any other unknown protocol.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.mastodon",
})
public class MastodonAddon {
}
