package de.mhus.vance.addon.brain.links;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class LinksAddonMeta implements VanceAddon {

    @Override public String id() { return "links"; }

    @Override public String displayName() { return "Links"; }
}
