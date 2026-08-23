package de.mhus.vance.addon.brain.mastodon;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class MastodonAddonMeta implements VanceAddon {

    @Override public String id() { return "mastodon"; }

    @Override public String displayName() { return "Mastodon feeds"; }
}
