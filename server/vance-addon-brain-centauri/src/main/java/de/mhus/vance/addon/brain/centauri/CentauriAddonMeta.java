package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class CentauriAddonMeta implements VanceAddon {

    @Override public String id() { return "centauri"; }

    @Override public String displayName() { return "Feeds"; }
}
