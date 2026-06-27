package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class WorkspaceAddonMeta implements VanceAddon {

    @Override public String id() { return "workspace"; }

    @Override public String displayName() { return "Workspace"; }
}
