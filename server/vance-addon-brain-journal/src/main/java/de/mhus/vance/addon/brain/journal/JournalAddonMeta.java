package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class JournalAddonMeta implements VanceAddon {

    @Override public String id() { return "journal"; }

    @Override public String displayName() { return "Journal"; }
}
