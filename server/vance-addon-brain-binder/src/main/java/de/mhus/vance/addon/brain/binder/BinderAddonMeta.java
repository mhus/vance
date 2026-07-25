package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class BinderAddonMeta implements VanceAddon {

    @Override public String id() { return "binder"; }

    @Override public String displayName() { return "Binder"; }
}
