package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class FinanceAddonMeta implements VanceAddon {

    @Override public String id() { return "finance"; }

    @Override public String displayName() { return "Finance Tree"; }
}
