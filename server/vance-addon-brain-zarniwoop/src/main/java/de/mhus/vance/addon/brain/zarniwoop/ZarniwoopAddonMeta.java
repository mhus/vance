package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class ZarniwoopAddonMeta implements VanceAddon {

    @Override public String id() { return "zarniwoop"; }

    @Override public String displayName() { return "Search"; }
}
