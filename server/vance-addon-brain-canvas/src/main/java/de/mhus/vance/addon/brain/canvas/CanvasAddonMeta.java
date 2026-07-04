package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class CanvasAddonMeta implements VanceAddon {

    @Override public String id() { return "canvas"; }

    @Override public String displayName() { return "Canvas"; }
}
