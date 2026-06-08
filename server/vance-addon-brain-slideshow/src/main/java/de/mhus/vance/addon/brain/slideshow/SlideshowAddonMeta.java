package de.mhus.vance.addon.brain.slideshow;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class SlideshowAddonMeta implements VanceAddon {

    @Override public String id() { return "slideshow"; }

    @Override public String displayName() { return "Slideshow"; }
}
