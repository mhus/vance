package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class ScribbleAddonMeta implements VanceAddon {

    @Override
    public String id() {
        return "scribble";
    }

    @Override
    public String displayName() {
        return "Scribble";
    }
}
