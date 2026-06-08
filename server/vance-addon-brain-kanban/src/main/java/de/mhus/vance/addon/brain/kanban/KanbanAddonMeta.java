package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class KanbanAddonMeta implements VanceAddon {

    @Override public String id() { return "kanban"; }

    @Override public String displayName() { return "Kanban"; }
}
