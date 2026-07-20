package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class WikiAddonMeta implements VanceAddon {

    @Override public String id() { return "wiki"; }

    @Override public String displayName() { return "Wiki"; }
}
