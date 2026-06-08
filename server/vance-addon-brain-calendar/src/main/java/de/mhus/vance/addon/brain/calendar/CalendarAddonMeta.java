package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class CalendarAddonMeta implements VanceAddon {

    @Override public String id() { return "calendar"; }

    @Override public String displayName() { return "Calendar"; }
}
