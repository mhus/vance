package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class BistromathAddonMeta implements VanceAddon {

    @Override public String id() { return "bistromath"; }

    @Override public String displayName() { return "Bistromath"; }
}
