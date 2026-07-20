package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class CommonDesktopAddonMeta implements VanceAddon {

    @Override public String id() { return "common-desktop"; }

    @Override public String displayName() { return "Common Desktop"; }
}
