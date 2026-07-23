package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class GtdAddonMeta implements VanceAddon {

    @Override public String id() { return "gtd"; }

    @Override public String displayName() { return "GTD"; }
}
