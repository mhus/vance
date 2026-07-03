package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class WorkbookAddonMeta implements VanceAddon {

    @Override public String id() { return "workbook"; }

    @Override public String displayName() { return "Workbook"; }
}
