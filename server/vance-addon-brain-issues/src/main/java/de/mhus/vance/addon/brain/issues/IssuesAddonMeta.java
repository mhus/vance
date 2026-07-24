package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class IssuesAddonMeta implements VanceAddon {

    @Override public String id() { return "issues"; }

    @Override public String displayName() { return "Issues"; }
}
